package acn;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Adversarial wire tests and lifecycle tests, using actual localhost UDP sockets. */
final class ProtocolTests {
    static int checks;
    static final InetSocketAddress TARGET=new InetSocketAddress("127.0.0.1",Config.PORT);
    static void check(boolean condition,String message) { Main.require(condition,message); checks++; }

    static void run() throws Exception {
        checks=0; Main.makeFiles(); codec(); receiverValidation(); hashFailure(); dataRetryExhaustion(); lifecycle();
        System.out.println("PASS: "+checks+" packet, adversarial receiver, integrity, retry-budget and chat lifecycle checks.");
    }
    static void codec() throws Exception {
        Packet packet=new Packet(Packet.DATA,UUID.randomUUID(),7,new byte[]{1,2,3});
        Packet decoded=Packet.decode(packet.encode());
        check(decoded.id.equals(packet.id) && decoded.sequence==7 && Arrays.equals(decoded.payload,packet.payload),"Packet round trip failed");
        for(int offset:new int[]{0,4,5,6,8,24,28,32,38}) {
            byte[] raw=packet.encode(); raw[offset]^=1;
            boolean rejected=false; try { Packet.decode(raw); } catch(IOException e) { rejected=true; }
            check(rejected,"Malformed/corrupted packet accepted at offset "+offset);
        }
        for(int size:new int[]{0,35,1237}) {
            boolean rejected=false; try { Packet.decode(new byte[size]); } catch(IOException e) { rejected=true; }
            check(rejected,"Bad datagram length accepted");
        }
        Config overflow=new Config(); overflow.delayMs=Integer.MAX_VALUE; overflow.jitterMs=Integer.MAX_VALUE;
        boolean rejected=false; try { overflow.validate(); } catch(IllegalArgumentException e) { rejected=true; }
        check(rejected,"Overflowed delay accepted");
    }
    static DatagramSocket socket() throws IOException { return new DatagramSocket(new InetSocketAddress("127.0.0.1",0)); }
    static void send(DatagramSocket socket,Packet packet) throws IOException { sendRaw(socket,packet.encode()); }
    static void sendRaw(DatagramSocket socket,byte[] bytes) throws IOException { socket.send(new DatagramPacket(bytes,bytes.length,TARGET)); }
    static Packet receive(DatagramSocket socket,int type) throws IOException {
        socket.setSoTimeout(1500); byte[] bytes=new byte[1500]; DatagramPacket datagram=new DatagramPacket(bytes,bytes.length);
        socket.receive(datagram); Packet p=Packet.decode(Arrays.copyOf(bytes,datagram.getLength()));
        check(p.type==type,"Unexpected packet: "+Packet.name(p.type)); return p;
    }
    static void stop(Transfer.Receiver receiver,Thread thread) throws InterruptedException {
        receiver.close(); thread.join(5000); check(!thread.isAlive(),"Receiver did not exit");
    }
    static void receiverValidation() throws Exception {
        Path folder=Main.newRun("test-adversarial-receiver");
        Transfer.Receiver receiver=new Transfer.Receiver(new Config(),folder,Paths.get("received"));
        Thread thread=new Thread(receiver); thread.start();
        byte[] file=Files.readAllBytes(Config.input("partial.bin")); UUID id=UUID.randomUUID();
        try(DatagramSocket peer=socket(); DatagramSocket stranger=socket()) {
            byte[] oversized=Transfer.metadata("partial.bin",file,new Config());
            // Modified UTF-8 name length + ASCII name precede the declared file size.
            ByteBuffer.wrap(oversized).putLong(2+"partial.bin".length(),(long)Config.MAX_FILE+1);
            send(peer,new Packet(Packet.START,id,-1,oversized)); receive(peer,Packet.ERROR);
            check(receiver.id==null,"Invalid START committed session state");
            byte[] metadata=Transfer.metadata("partial.bin",file,new Config());
            send(peer,new Packet(Packet.START,id,-1,metadata)); receive(peer,Packet.READY);
            send(peer,new Packet(Packet.DATA,UUID.randomUUID(),0,Arrays.copyOfRange(file,0,1024)));
            send(stranger,new Packet(Packet.DATA,id,0,Arrays.copyOfRange(file,0,1024)));
            send(peer,new Packet(Packet.DATA,id,99,new byte[1]));
            send(peer,new Packet(Packet.DATA,id,0,new byte[5]));
            byte[] conflict=metadata.clone(); conflict[conflict.length-1]^=1;
            send(peer,new Packet(Packet.START,id,-1,conflict));
            send(peer,new Packet(Packet.FIN,id,-1,Transfer.digest(file))); receive(peer,Packet.ERROR);
            byte[] damaged=new Packet(Packet.DATA,id,0,Arrays.copyOfRange(file,0,1024)).encode(); damaged[32]^=1; sendRaw(peer,damaged);
            for(int seq:new int[]{1,1,0,2}) {
                send(peer,new Packet(Packet.DATA,id,seq,Arrays.copyOfRange(file,seq*1024,Math.min(file.length,(seq+1)*1024))));
                check(receive(peer,Packet.ACK).sequence==seq,"ACK sequence differs");
            }
            send(peer,new Packet(Packet.FIN,id,-1,Transfer.digest(file))); receive(peer,Packet.DONE);
            send(peer,new Packet(Packet.FIN,id,-1,Transfer.digest(file))); receive(peer,Packet.DONE);
        } finally { stop(receiver,thread); }
        check(receiver.failure==null,"Receiver failed after invalid traffic");
        check(Arrays.equals(file,Files.readAllBytes(receiver.result)),"Invalid traffic changed reconstructed bytes");
        check(receiver.metrics.payloadBytes==file.length,"Duplicate DATA counted twice");
        check(receiver.metrics.dataDuplicates==1 && receiver.metrics.controlDuplicates==1,"Duplicate DATA/FIN not separated");
        check(receiver.metrics.outOfOrder==1,"Out-of-order DATA not measured");
        check(receiver.metrics.invalid==1,"Corruption not counted");
        long ignored=0; for(Map<String,String> e:EvidenceAudit.readCsv(folder.resolve("receiver-events.csv"))) if("IGNORE".equals(e.get("event"))) ignored++;
        check(ignored>=6,"Malformed/foreign requests not ignored");
    }
    static void hashFailure() throws Exception {
        Path folder=Main.newRun("test-end-to-end-hash");
        Transfer.Receiver receiver=new Transfer.Receiver(new Config(),folder,Paths.get("received"));
        Thread thread=new Thread(receiver); thread.start();
        byte[] file=Files.readAllBytes(Config.input("hello.txt")),altered=file.clone(); altered[0]^=1;
        UUID id=UUID.randomUUID();
        try(DatagramSocket peer=socket()) {
            send(peer,new Packet(Packet.START,id,-1,Transfer.metadata("hello.txt",file,new Config()))); receive(peer,Packet.READY);
            // The altered DATA has a VALID CRC. Only the whole-file hash can detect this mismatch.
            send(peer,new Packet(Packet.DATA,id,0,altered)); receive(peer,Packet.ACK);
            send(peer,new Packet(Packet.FIN,id,-1,Transfer.digest(file))); receive(peer,Packet.ERROR);
        } finally { stop(receiver,thread); }
        check(receiver.failure!=null && receiver.result==null,"Invalid whole-file hash produced a completed file");
        check("FAILED".equals(receiver.metrics.state),"Hash mismatch did not fail the receiver");
        check(!Files.exists(Paths.get("received",id+".part")),"Hash failure left a partial file");
    }
    static void dataRetryExhaustion() throws Exception {
        Path folder=Main.newRun("test-data-retry-budget");
        Config c=new Config(); c.timeoutMs=30; c.maxRetries=2;
        Transfer.Sender sender=new Transfer.Sender(c,folder);
        AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
        try(DatagramSocket fakeReceiver=new DatagramSocket(TARGET)) {
            Thread thread=new Thread(() -> { try { sender.send("hello.txt"); } catch(Throwable e) { failure.set(e); } });
            long started=System.nanoTime(); thread.start();
            fakeReceiver.setSoTimeout(1500); byte[] raw=new byte[1500]; DatagramPacket incoming=new DatagramPacket(raw,raw.length);
            fakeReceiver.receive(incoming); Packet start=Packet.decode(Arrays.copyOf(raw,incoming.getLength()));
            byte[] ready=new Packet(Packet.READY,start.id,-1,ByteBuffer.allocate(4).putInt(1024).array()).encode();
            fakeReceiver.send(new DatagramPacket(ready,ready.length,incoming.getSocketAddress()));
            // Intentionally never acknowledge any DATA.
            thread.join(5000);
            check(!thread.isAlive() && failure.get() instanceof IOException,"DATA retry limit did not stop the sender");
            check((System.nanoTime()-started)/1e9<5,"DATA failure exceeded its bounded budget");
        }
        check("FAILED".equals(sender.metrics.state),"Failed sender reports success");
        check(sender.metrics.dataRetransmissions==2 && sender.metrics.payloadBytes==0,"Retry budget/confirmed bytes incorrect");
    }
    static void lifecycle() throws Exception {
        try(ChatConsole chat=new ChatConsole()) {
            Command c=Command.validate(InterfaceTests.GOOD.replace("hello.txt","small.bin").replace("baseline","delayed").replace("32768","1024"));
            chat.launch(c);
            boolean busy=false; try { chat.launch(c); } catch(IllegalStateException expected) { busy=true; }
            check(busy,"Overlapping transfer accepted"); chat.waitForTransfer();
            check("COMPLETED".equals(chat.snapshot().get("state")),"Completed chat lifecycle missing");
            // Bind failure occurs before Sender metrics exist: it must still produce a FAILED snapshot.
            try(DatagramSocket occupied=new DatagramSocket(TARGET)) {
                chat.launch(c); chat.waitForTransfer();
                check("FAILED".equals(chat.snapshot().get("state")),"Startup failure was hidden or replaced by old results");
                check(chat.snapshot().containsKey("error"),"Startup failure has no explanation");
            }
        }
    }
}
