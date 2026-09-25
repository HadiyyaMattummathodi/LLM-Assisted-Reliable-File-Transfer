package acn;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Real UDP plus a documented outgoing impairment shim, independently on each side. */
final class Link implements Closeable {
    final DatagramSocket socket;
    final Config config;
    final Metrics metrics;
    final Random random;
    final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    final Set<Emission> scheduled=Collections.newSetFromMap(new ConcurrentHashMap<Emission,Boolean>());
    volatile IOException asynchronousError;
    boolean droppedFirst,corruptedFirst;
    Link(int port,Config config,Metrics metrics) throws IOException {
        this.config=config; this.metrics=metrics;
        random=new Random(config.seed+(metrics.role.equals("receiver")?1000003:0));
        socket=new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));
        socket.setReceiveBufferSize(1024*1024); socket.setSoTimeout(10);
    }
    synchronized void send(final Packet p,final InetSocketAddress target) throws IOException {
        checkError();
        final byte[] bytes=p.encode(); metrics.event("SEND",p,bytes.length,"");
        boolean forcedDrop=!droppedFirst && p.type==config.dropFirstType;
        if(forcedDrop) droppedFirst=true;
        if(forcedDrop || random.nextDouble()<config.loss) {
            metrics.event("DROP",p,bytes.length,forcedDrop?"forced test fault":"random shim loss"); return;
        }
        boolean forcedCorruption=!corruptedFirst && p.type==config.corruptFirstType;
        if(forcedCorruption) corruptedFirst=true;
        if(forcedCorruption || random.nextDouble()<config.corruption) {
            bytes[bytes.length-1]^=1; // Change bytes AFTER CRC calculation.
            metrics.event("CORRUPT",p,bytes.length,forcedCorruption?"forced test fault":"random shim corruption");
        }
        int delay=config.delayMs+(config.jitterMs==0?0:random.nextInt(config.jitterMs+1));
        Emission emit=new Emission(p,bytes,target);
        if(delay==0) { emit.run(); checkError(); }
        else { scheduled.add(emit); timer.schedule(emit,delay,TimeUnit.MILLISECONDS); }
    }
    Received receive(int timeoutMs) throws IOException {
        checkError(); socket.setSoTimeout(Math.max(1,timeoutMs));
        // One extra byte makes an oversized/truncated datagram fail validation.
        byte[] buffer=new byte[Packet.HEADER+Packet.MAX_PAYLOAD+1];
        DatagramPacket datagram=new DatagramPacket(buffer,buffer.length);
        try { socket.receive(datagram); }
        catch(SocketTimeoutException e) { return null; }
        byte[] raw=Arrays.copyOfRange(datagram.getData(),datagram.getOffset(),datagram.getOffset()+datagram.getLength());
        Packet p;
        try { p=Packet.decode(raw); }
        catch(IOException e) { metrics.event("RECEIVE_INVALID",null,raw.length,e.getMessage()); return null; }
        metrics.event("RECEIVE",p,raw.length,"");
        return new Received(p,(InetSocketAddress)datagram.getSocketAddress());
    }
    void checkError() throws IOException { if(asynchronousError!=null) throw asynchronousError; }
    public void close() {
        timer.shutdownNow();
        try { timer.awaitTermination(3,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        for(Emission emission:scheduled)
            metrics.event("CANCEL",emission.packet,emission.bytes.length,"queued at endpoint shutdown; never emitted");
        scheduled.clear();
        socket.close();
    }
    final class Emission implements Runnable {
        final Packet packet;
        final byte[] bytes;
        final InetSocketAddress target;
        Emission(Packet packet,byte[] bytes,InetSocketAddress target) {
            this.packet=packet; this.bytes=bytes; this.target=target;
        }
        public void run() {
            try {
                socket.send(new DatagramPacket(bytes,bytes.length,target));
                metrics.event("EMIT",packet,bytes.length,"");
            } catch(IOException e) {
                asynchronousError=e; metrics.event("SEND_ERROR",packet,bytes.length,e.toString());
            } finally { scheduled.remove(this); }
        }
    }
    static final class Received {
        final Packet packet; final InetSocketAddress address;
        Received(Packet packet,InetSocketAddress address) { this.packet=packet; this.address=address; }
    }
}
