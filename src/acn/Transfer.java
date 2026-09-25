package acn;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Sender and receiver implement selective-repeat ARQ with individual ACKs. */
final class Transfer {
    static byte[] digest(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String hex(byte[] data) {
        StringBuilder s=new StringBuilder(); for(byte b:data) s.append(String.format("%02x",b&255)); return s.toString();
    }
    static byte[] metadata(String name,byte[] file,Config c) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeUTF(name); out.writeLong(file.length); out.writeInt(c.chunk); out.writeInt(c.windowBytes);
        out.writeInt(c.timeoutMs); out.writeInt(c.maxRetries); out.write(digest(file)); out.flush();
        return bytes.toByteArray();
    }
    static void ignored(Metrics metrics,Packet p,String why) { metrics.event("IGNORE",p,0,why); }

    static final class Pending {
        final Packet packet; long sentAt; int retries;
        Pending(Packet p) { packet=p; }
    }

    static final class Sender {
        final Config config;
        final Path folder;
        volatile Metrics metrics;
        Sender(Config config,Path folder) { this.config=config; this.folder=folder; }

        Map<String,String> send(String filename) throws Exception {
            config.validate(); // Check before touching a file or opening a socket.
            Path input=Config.input(filename);
            byte[] file=Files.readAllBytes(input);
            if(file.length>Config.MAX_FILE) throw new IOException("File exceeds 16 MiB");
            UUID id=UUID.randomUUID();
            metrics=new Metrics(folder,"sender"); metrics.filename=filename; metrics.fileSize=file.length;
            metrics.transferId=id.toString(); metrics.sha256=hex(digest(file));
            try(Link link=new Link(0,config,metrics)) {
                InetSocketAddress destination=new InetSocketAddress("127.0.0.1",Config.PORT);
                Packet start=new Packet(Packet.START,id,-1,metadata(filename,file,config));
                Packet ready=exchange(link,start,Packet.READY,destination);
                if(ready.payload.length!=4) throw new IOException("Bad READY length");
                int agreed=ByteBuffer.wrap(ready.payload).getInt();
                if(agreed<256 || agreed>config.chunk) throw new IOException("Invalid negotiated chunk size");
                config.chunk=agreed; config.validate();
                int total=(file.length+config.chunk-1)/config.chunk;
                int base=0,next=0;
                BitSet confirmed=new BitSet(total);
                Map<Integer,Pending> pending=new LinkedHashMap<Integer,Pending>();
                while(base<total) {
                    // Limit the sequence range, not merely the number of unacknowledged packets.
                    while(next<total && next<base+config.packetsInWindow()) {
                        int from=next*config.chunk, to=Math.min(file.length,from+config.chunk);
                        Pending p=new Pending(new Packet(Packet.DATA,id,next,Arrays.copyOfRange(file,from,to)));
                        pending.put(next,p); p.sentAt=System.nanoTime(); link.send(p.packet,destination); next++;
                    }
                    Link.Received received=link.receive(Math.min(10,config.timeoutMs));
                    if(received!=null) {
                        Packet ack=received.packet;
                        if(!received.address.equals(destination) || !ack.id.equals(id)) ignored(metrics,ack,"wrong peer or transfer");
                        else if(ack.type==Packet.ERROR) throw new IOException("Receiver: "+new String(ack.payload,StandardCharsets.UTF_8));
                        else if(ack.type==Packet.ACK && ack.payload.length==0 && ack.sequence>=0 && ack.sequence<next) {
                            Pending p=pending.remove(ack.sequence);
                            if(p==null) metrics.event("DUPLICATE",ack,0,"duplicate ACK");
                            else {
                                metrics.event("ACKNOWLEDGED",ack,0,"");
                                metrics.event("CONFIRM_DATA",ack,p.packet.payload.length,"unique file bytes acknowledged");
                                // Karn's rule: an ACK after retransmission has an ambiguous send time.
                                if(p.retries==0) metrics.rtt(ack,(System.nanoTime()-p.sentAt)/1e6);
                                confirmed.set(ack.sequence); while(base<total && confirmed.get(base)) base++;
                            }
                        } else ignored(metrics,ack,"unexpected response");
                    }
                    long now=System.nanoTime();
                    for(Pending p:pending.values()) {
                        if(now-p.sentAt>=config.timeoutMs*1000000L) {
                            metrics.event("TIMEOUT",p.packet,0,"");
                            if(p.retries>=config.maxRetries) throw new IOException("Retry limit for DATA "+p.packet.sequence);
                            p.retries++; metrics.event("RETRANSMIT",p.packet,0,"");
                            p.sentAt=System.nanoTime(); link.send(p.packet,destination);
                        }
                    }
                }
                Packet done=exchange(link,new Packet(Packet.FIN,id,-1,digest(file)),Packet.DONE,destination);
                if(!Arrays.equals(done.payload,digest(file))) throw new IOException("Final SHA-256 mismatch");
                metrics.finish("COMPLETED");
                System.out.println("SUCCESS: "+filename+" | "+file.length+" bytes | SHA-256 verified");
            } catch(Exception e) { metrics.finish("FAILED"); throw e; }
            finally {
                try { metrics.close(); }
                finally { Metrics.writeCsv(folder.resolve("sender-summary.csv"),Collections.singletonList(metrics.summary(config))); }
            }
            return metrics.summary(config);
        }

        Packet exchange(Link link,Packet request,int responseType,InetSocketAddress target) throws IOException {
            for(int attempt=0;attempt<=config.maxRetries;attempt++) {
                if(attempt>0) metrics.event("RETRANSMIT",request,0,"");
                long deadline=System.nanoTime()+config.timeoutMs*1000000L;
                link.send(request,target);
                while(System.nanoTime()<deadline) {
                    int remaining=(int)Math.max(1,(deadline-System.nanoTime()+999999)/1000000);
                    Link.Received r=link.receive(Math.min(remaining,50));
                    if(r==null) continue;
                    Packet p=r.packet;
                    if(!r.address.equals(target) || !p.id.equals(request.id)) { ignored(metrics,p,"wrong peer or transfer"); continue; }
                    if(p.type==Packet.ERROR) throw new IOException("Receiver: "+new String(p.payload,StandardCharsets.UTF_8));
                    if(p.type==responseType && p.sequence==-1) {
                        metrics.event("ACKNOWLEDGED",p,0,""); return p;
                    }
                    ignored(metrics,p,"waiting for "+Packet.name(responseType));
                }
                metrics.event("TIMEOUT",request,0,"");
            }
            throw new IOException("Retry limit waiting for "+Packet.name(responseType));
        }
    }

    static final class Receiver implements Runnable,Closeable {
        final Config config;
        final Path folder,output;
        final Metrics metrics;
        final Link link;
        volatile boolean stopped;
        volatile Exception failure;
        volatile Path result;
        UUID id;
        InetSocketAddress peer;
        byte[] file,expectedHash,startPayload;
        BitSet seen;
        int total,startRequests,nextExpected;
        long lastActivity=System.nanoTime(),completedAt;
        Receiver(Config config,Path folder,Path output) throws IOException {
            config.validate(); this.config=config; this.folder=folder; this.output=output;
            metrics=new Metrics(folder,"receiver");
            Link created;
            try { created=new Link(Config.PORT,config,metrics); }
            catch(IOException e) { metrics.close(); throw e; }
            link=created;
        }
        public void run() {
            try {
                System.out.println("Receiver listening on 127.0.0.1:"+Config.PORT);
                while(!stopped) {
                    long now=System.nanoTime();
                    // Keep DONE available through the sender's entire bounded FIN retry horizon.
                    long lingerMs=(config.maxRetries+1L)*config.timeoutMs+2L*(config.delayMs+config.jitterMs)+250;
                    if(completedAt!=0 && now-completedAt>lingerMs*1000000L) break;
                    if(completedAt==0 && now-lastActivity>120000000000L) throw new IOException("Receiver idle timeout");
                    Link.Received r=link.receive(50); if(r==null) continue;
                    Packet p=r.packet;
                    if(id==null) {
                        if(p.type!=Packet.START || p.sequence!=-1) { ignored(metrics,p,"START required"); continue; }
                        try { acceptStart(p,r.address); }
                        catch(IllegalArgumentException | IOException e) {
                            link.send(new Packet(Packet.ERROR,p.id,-1,"Invalid metadata".getBytes(StandardCharsets.UTF_8)),r.address);
                            ignored(metrics,p,"invalid metadata"); continue;
                        }
                    }
                    if(!id.equals(p.id) || !peer.equals(r.address)) { ignored(metrics,p,"wrong peer or transfer"); continue; }
                    lastActivity=System.nanoTime();
                    if(p.type==Packet.START && p.sequence==-1) {
                        if(!Arrays.equals(p.payload,startPayload)) { ignored(metrics,p,"conflicting START"); continue; }
                        if(startRequests++>0) metrics.event("DUPLICATE",p,0,"repeated START");
                        link.send(new Packet(Packet.READY,id,-1,ByteBuffer.allocate(4).putInt(config.chunk).array()),peer);
                    } else if(p.type==Packet.DATA) acceptData(p);
                    else if(p.type==Packet.FIN && p.sequence==-1) complete(p);
                    else ignored(metrics,p,"unexpected request");
                }
                if(completedAt==0) metrics.finish("ABORTED");
            } catch(Exception e) {
                failure=e; metrics.finish("FAILED"); System.err.println("Receiver error: "+e.getMessage());
            } finally {
                link.close();
                try { Metrics.writeCsv(folder.resolve("receiver-summary.csv"),Collections.singletonList(metrics.summary(config))); }
                catch(IOException e) { failure=e; }
                try { metrics.close(); }
                catch(UncheckedIOException e) { failure=e; }
            }
        }
        void acceptStart(Packet p,InetSocketAddress address) throws IOException {
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(p.payload));
            String name=in.readUTF(); long size=in.readLong();
            Config requested=new Config(); requested.chunk=in.readInt(); requested.windowBytes=in.readInt();
            requested.timeoutMs=in.readInt(); requested.maxRetries=in.readInt(); requested.validate();
            byte[] hash=new byte[32]; in.readFully(hash);
            Config.validateName(name);
            if(size<0 || size>Config.MAX_FILE || in.available()!=0) throw new IOException("Bad metadata");
            // Commit session state only after every field has been validated.
            config.chunk=Math.min(requested.chunk,1024); config.windowBytes=requested.windowBytes;
            config.timeoutMs=requested.timeoutMs; config.maxRetries=requested.maxRetries;
            file=new byte[(int)size]; expectedHash=hash; id=p.id; peer=address; startPayload=p.payload;
            total=(file.length+config.chunk-1)/config.chunk; seen=new BitSet(total);
            metrics.filename=name; metrics.fileSize=size; metrics.transferId=id.toString();
            metrics.event("START_ACCEPTED",p,0,"negotiated chunk="+config.chunk);
        }
        void acceptData(Packet p) throws IOException {
            int seq=p.sequence;
            if(seq<0 || seq>=total) { ignored(metrics,p,"sequence outside file"); return; }
            int offset=seq*config.chunk,expected=Math.min(config.chunk,file.length-offset);
            if(p.payload.length!=expected) { ignored(metrics,p,"incorrect chunk length"); return; }
            if(seen.get(seq)) metrics.event("DUPLICATE",p,0,"duplicate DATA; acknowledge without writing twice");
            else {
                if(seq>nextExpected) metrics.event("OUT_OF_ORDER",p,0,"arrived before missing sequence="+nextExpected);
                System.arraycopy(p.payload,0,file,offset,p.payload.length); seen.set(seq);
                while(nextExpected<total && seen.get(nextExpected)) nextExpected++;
                metrics.event("ACCEPT_DATA",p,p.payload.length,"unique file bytes received");
            }
            link.send(Packet.empty(Packet.ACK,id,seq),peer);
        }
        void complete(Packet p) throws IOException {
            if(!Arrays.equals(p.payload,expectedHash) || seen.cardinality()!=total) {
                link.send(new Packet(Packet.ERROR,id,-1,"Incomplete file or invalid FIN".getBytes(StandardCharsets.UTF_8)),peer); return;
            }
            if(completedAt==0) {
                byte[] actual=digest(file);
                if(!Arrays.equals(actual,expectedHash)) {
                    link.send(new Packet(Packet.ERROR,id,-1,"File SHA-256 mismatch".getBytes(StandardCharsets.UTF_8)),peer);
                    throw new IOException("Reconstructed file hash mismatch");
                }
                Files.createDirectories(output);
                Path target=output.resolve(id+"-"+metrics.filename);
                Path temporary=output.resolve(id+".part");
                try {
                    Files.write(temporary,file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                    try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE); }
                    catch(AtomicMoveNotSupportedException e) { Files.move(temporary,target); }
                } finally { Files.deleteIfExists(temporary); }
                result=target; metrics.sha256=hex(actual); metrics.finish("VERIFIED"); completedAt=System.nanoTime();
                metrics.event("FILE_VERIFIED",p,file.length,"SHA-256="+metrics.sha256);
                System.out.println("Received and verified: "+target);
            } else metrics.event("DUPLICATE",p,0,"repeat FIN; resend DONE");
            link.send(new Packet(Packet.DONE,id,-1,expectedHash),peer);
        }
        public void close() { stopped=true; }
    }
}
