package net.minecraft.server;

import java.net.SocketAddress;
import java.util.Queue;
import javax.crypto.SecretKey;

import com.google.common.collect.Queues;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.timeout.TimeoutException;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

public class NetworkManager extends SimpleChannelInboundHandler {

    private static final Logger i = LogManager.getLogger();
    public static final Marker a = MarkerManager.getMarker("NETWORK");
    public static final Marker b = MarkerManager.getMarker("NETWORK_PACKETS", a);
    public static final Marker c = MarkerManager.getMarker("NETWORK_STAT", a);
    public static final AttributeKey d = new AttributeKey("protocol");
    public static final AttributeKey e = new AttributeKey("receivable_packets");
    public static final AttributeKey f = new AttributeKey("sendable_packets");
    public static final NioEventLoopGroup g = new NioEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Client IO #%d").setDaemon(true).build());
    public static final NetworkStatistics h = new NetworkStatistics();
    private final boolean j;
    private final Queue k = Queues.newConcurrentLinkedQueue();
    private final Queue l = Queues.newConcurrentLinkedQueue();
    private Channel m;
    private SocketAddress n;
    private PacketListener o;
    private EnumProtocol p;
    private IChatBaseComponent q;
    private boolean r;

    public NetworkManager(boolean flag) {
        this.j = flag;
    }

    public void channelActive(ChannelHandlerContext channelhandlercontext) {
        super.channelActive(channelhandlercontext);
        this.m = channelhandlercontext.channel();
        this.n = this.m.remoteAddress();
        this.a(EnumProtocol.HANDSHAKING);
    }

    public void a(EnumProtocol enumprotocol) {
        this.p = (EnumProtocol) this.m.attr(d).getAndSet(enumprotocol);
        this.m.attr(e).set(enumprotocol.a(this.j));
        this.m.attr(f).set(enumprotocol.b(this.j));
        this.m.config().setAutoRead(true);
        i.debug("Enabled auto read");
    }

    public void channelInactive(ChannelHandlerContext channelhandlercontext) {
        this.close(new ChatMessage("disconnect.endOfStream", new Object[0]));
    }

    public void exceptionCaught(ChannelHandlerContext channelhandlercontext, Throwable throwable) {
        ChatMessage chatmessage;

        if (throwable instanceof TimeoutException) {
            chatmessage = new ChatMessage("disconnect.timeout", new Object[0]);
        } else {
            chatmessage = new ChatMessage("disconnect.genericReason", new Object[] { "Internal Exception: " + throwable});
        }

        this.close(chatmessage);
    }

    protected void a(ChannelHandlerContext channelhandlercontext, Packet packet) {
        if (this.m.isOpen()) {
            if (packet.a()) {
                packet.handle(this.o);
            } else {
                this.k.add(packet);
            }
        }
    }

    public void a(PacketListener packetlistener) {
        Validate.notNull(packetlistener, "packetListener", new Object[0]);
        i.debug("Set listener of {} to {}", new Object[] { this, packetlistener});
        this.o = packetlistener;
    }

    public void handle(Packet packet, GenericFutureListener... agenericfuturelistener) {
        if (this.m != null && this.m.isOpen()) {
            this.i();
            this.b(packet, agenericfuturelistener);
        } else {
            this.l.add(new QueuedPacket(packet, agenericfuturelistener));
        }
    }

    private void b(Packet packet, GenericFutureListener[] agenericfuturelistener) {
        EnumProtocol enumprotocol = EnumProtocol.a(packet);
        EnumProtocol enumprotocol1 = (EnumProtocol) this.m.attr(d).get();

        if (enumprotocol1 != enumprotocol) {
            i.debug("Disabled auto read");
            this.m.config().setAutoRead(false);
        }

        if (this.m.eventLoop().inEventLoop()) {
            if (enumprotocol != enumprotocol1) {
                this.a(enumprotocol);
            }

            this.m.writeAndFlush(packet).addListeners(agenericfuturelistener).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        } else {
            this.m.eventLoop().execute(new QueuedProtocolSwitch(this, enumprotocol, enumprotocol1, packet, agenericfuturelistener));
        }
    }

    private void i() {
        if (this.m != null && this.m.isOpen()) {
            while (!this.l.isEmpty()) {
                QueuedPacket queuedpacket = (QueuedPacket) this.l.poll();

                this.b(QueuedPacket.a(queuedpacket), QueuedPacket.b(queuedpacket));
            }
        }
    }

    public void a() {
        this.i();
        EnumProtocol enumprotocol = (EnumProtocol) this.m.attr(d).get();

        if (this.p != enumprotocol) {
            if (this.p != null) {
                this.o.a(this.p, enumprotocol);
            }

            this.p = enumprotocol;
        }

        if (this.o != null) {
            for (int i = 1000; !this.k.isEmpty() && i >= 0; --i) {
                Packet packet = (Packet) this.k.poll();

                packet.handle(this.o);
            }

            this.o.a();
        }

        this.m.flush();
    }

    public SocketAddress getSocketAddress() {
        return this.n;
    }

    public void close(IChatBaseComponent ichatbasecomponent) {
        if (this.m.isOpen()) {
            this.m.close();
            this.q = ichatbasecomponent;
        }
    }

    public boolean c() {
        return this.m instanceof LocalChannel || this.m instanceof LocalServerChannel;
    }

    public void a(SecretKey secretkey) {
        this.m.pipeline().addBefore("splitter", "decrypt", new PacketDecrypter(MinecraftEncryption.a(2, secretkey)));
        this.m.pipeline().addBefore("prepender", "encrypt", new PacketEncrypter(MinecraftEncryption.a(1, secretkey)));
        this.r = true;
    }

    public boolean isConnected() {
        return this.m != null && this.m.isOpen();
    }

    public PacketListener getPacketListener() {
        return this.o;
    }

    public IChatBaseComponent f() {
        return this.q;
    }

    public void g() {
        this.m.config().setAutoRead(false);
    }

    protected void channelRead0(ChannelHandlerContext channelhandlercontext, Object object) {
        this.a(channelhandlercontext, (Packet) object);
    }

    static Channel a(NetworkManager networkmanager) {
        return networkmanager.m;
    }
}
