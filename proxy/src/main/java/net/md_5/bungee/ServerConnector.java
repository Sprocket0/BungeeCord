package net.md_5.bungee;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import io.netty.channel.Channel;
import java.util.Queue;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.config.TexturePackInfo;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.scoreboard.Objective;
import net.md_5.bungee.api.scoreboard.Team;
import net.md_5.bungee.connection.CancelSendSignal;
import net.md_5.bungee.connection.DownstreamBridge;
import net.md_5.bungee.netty.HandlerBoss;
import net.md_5.bungee.packet.DefinedPacket;
import net.md_5.bungee.packet.Packet1Login;
import net.md_5.bungee.packet.Packet9Respawn;
import net.md_5.bungee.packet.PacketCDClientStatus;
import net.md_5.bungee.packet.PacketCEScoreboardObjective;
import net.md_5.bungee.packet.PacketD1Team;
import net.md_5.bungee.packet.PacketFAPluginMessage;
import net.md_5.bungee.packet.PacketFDEncryptionRequest;
import net.md_5.bungee.packet.PacketFFKick;
import net.md_5.bungee.packet.PacketHandler;

@RequiredArgsConstructor
public class ServerConnector extends PacketHandler
{

    private final ProxyServer bungee;
    private Channel ch;
    private final UserConnection user;
    private final ServerInfo target;
    private State thisState = State.ENCRYPT_REQUEST;

    private enum State
    {

        ENCRYPT_REQUEST, LOGIN, FINISHED;
    }

    @Override
    public void connected(Channel channel) throws Exception
    {
        this.ch = channel;

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF( "Login" );
        out.writeUTF( user.getAddress().getAddress().getHostAddress() );
        out.writeInt( user.getAddress().getPort() );
        channel.write( new PacketFAPluginMessage( "BungeeCord", out.toByteArray() ) );

        channel.write( user.handshake );
        channel.write( PacketCDClientStatus.CLIENT_LOGIN );
    }

    @Override
    public void handle(Packet1Login login) throws Exception
    {
        Preconditions.checkState( thisState == State.LOGIN, "Not exepcting LOGIN" );

        ServerConnection server = new ServerConnection( ch, target, login );
        ServerConnectedEvent event = new ServerConnectedEvent( user, server );
        bungee.getPluginManager().callEvent( event );

        ch.write( BungeeCord.getInstance().registerChannels() );

        // TODO: Race conditions with many connects
        Queue<DefinedPacket> packetQueue = ( (BungeeServerInfo) target ).getPacketQueue();
        while ( !packetQueue.isEmpty() )
        {
            ch.write( packetQueue.poll() );
        }
        if ( user.settings != null )
        {
            ch.write( user.settings );
        }

        synchronized ( user.getSwitchMutex() )
        {
            if ( user.getServer() == null )
            {
                BungeeCord.getInstance().connections.put( user.getName(), user );
                bungee.getTabListHandler().onConnect( user );
                // Once again, first connection
                user.clientEntityId = login.entityId;
                user.serverEntityId = login.entityId;
                // Set tab list size
                Packet1Login modLogin = new Packet1Login(
                        login.entityId,
                        login.levelType,
                        login.gameMode,
                        (byte) login.dimension,
                        login.difficulty,
                        login.unused,
                        (byte) user.getPendingConnection().getListener().getTabListSize() );
                user.ch.write( modLogin );
                ch.write( BungeeCord.getInstance().registerChannels() );

                TexturePackInfo texture = user.getPendingConnection().getListener().getTexturePack();
                if ( texture != null )
                {
                    ch.write( new PacketFAPluginMessage( "MC|TPack", ( texture.getUrl() + "\00" + texture.getSize() ).getBytes() ) );
                }
            } else
            {
                bungee.getTabListHandler().onServerChange( user );

                for ( Objective objective : user.serverSentScoreboard.getObjectives() )
                {
                    user.ch.write( new PacketCEScoreboardObjective( objective.getName(), objective.getValue(), (byte) 1 ) );
                }
                for ( Team team : user.serverSentScoreboard.getTeams() )
                {
                    user.ch.write( PacketD1Team.destroy( team.getName() ) );
                }
                user.serverSentScoreboard.clear();

                user.sendPacket( Packet9Respawn.DIM1_SWITCH );
                user.sendPacket( Packet9Respawn.DIM2_SWITCH );

                user.serverEntityId = login.entityId;
                user.ch.write( new Packet9Respawn( login.dimension, login.difficulty, login.gameMode, (short) 256, login.levelType ) );

                // Remove from old servers
                user.getServer().setObsolete( true );
                user.getServer().disconnect( "Quitting" );
            }

            // TODO: Fix this?
            if ( !user.ch.isActive() )
            {
                server.disconnect( "Quitting" );
                // Silly server admins see stack trace and die
                bungee.getLogger().warning( "No client connected for pending server!" );
                return;
            }

            // Add to new server
            // TODO: Move this to the connected() method of DownstreamBridge
            target.addPlayer( user );

            user.setServer( server );
            ch.pipeline().get( HandlerBoss.class ).setHandler( new DownstreamBridge( bungee, user, server ) );
        }

        thisState = State.FINISHED;

        throw new CancelSendSignal();
    }

    @Override
    public void handle(PacketFDEncryptionRequest encryptRequest) throws Exception
    {
        Preconditions.checkState( thisState == State.ENCRYPT_REQUEST, "Not expecting ENCRYPT_REQUEST" );
        thisState = State.LOGIN;
    }

    @Override
    public void handle(PacketFFKick kick) throws Exception
    {
        ServerInfo def = bungee.getServerInfo( user.getPendingConnection().getListener().getDefaultServer() );
        if ( target == def )
        {
            def = null;
        }
        ServerKickEvent event = bungee.getPluginManager().callEvent( new ServerKickEvent( user, kick.message, def ) );
        if ( event.isCancelled() && event.getCancelServer() != null )
        {
            user.connect( event.getCancelServer() );
            return;
        }

        String message = ChatColor.RED + "Kicked whilst connecting to " + target.getName() + ": " + kick.message;
        if ( user.getServer() == null )
        {
            user.disconnect( message );
        } else
        {
            user.sendMessage( message );
        }
    }

    @Override
    public String toString()
    {
        return "[" + user.getName() + "] <-> ServerConnector [" + target.getName() + "]";
    }
}
