package acn;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.zip.CRC32;

/** Our application protocol. A UDP datagram contains exactly one Packet. */
final class Packet {
    static final int START=1, READY=2, DATA=3, ACK=4, FIN=5, DONE=6, ERROR=7;
    static final int MAGIC=0x41434E31, HEADER=36, MAX_PAYLOAD=1200;
    final int type, sequence;
    final UUID id;
    final byte[] payload;

    Packet(int type, UUID id, int sequence, byte[] payload) {
        this.type=type; this.id=id; this.sequence=sequence; this.payload=payload;
    }
    static Packet empty(int type, UUID id, int sequence) {
        return new Packet(type,id,sequence,new byte[0]);
    }
    byte[] encode() {
        ByteBuffer b=ByteBuffer.allocate(HEADER+payload.length);
        b.putInt(MAGIC).put((byte)1).put((byte)type).putShort((short)0);
        b.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        b.putInt(sequence).putInt(payload.length).put(payload);
        CRC32 crc=new CRC32(); crc.update(b.array(),0,b.position());
        b.putInt((int)crc.getValue());
        return b.array();
    }
    static Packet decode(byte[] raw) throws IOException {
        if(raw.length<HEADER || raw.length>HEADER+MAX_PAYLOAD) throw new IOException("invalid length");
        CRC32 crc=new CRC32(); crc.update(raw,0,raw.length-4);
        ByteBuffer b=ByteBuffer.wrap(raw);
        if(b.getInt()!=MAGIC || b.get()!=1) throw new IOException("unknown protocol");
        int type=b.get() & 255;
        if(b.getShort()!=0 || type<START || type>ERROR) throw new IOException("invalid header");
        UUID id=new UUID(b.getLong(),b.getLong());
        int seq=b.getInt(), length=b.getInt();
        if(length<0 || raw.length!=HEADER+length) throw new IOException("invalid payload length");
        byte[] payload=new byte[length]; b.get(payload);
        if(b.getInt()!=(int)crc.getValue()) throw new IOException("CRC mismatch");
        return new Packet(type,id,seq,payload);
    }
    static String name(int type) {
        String[] names={"UNKNOWN","START","READY","DATA","ACK","FIN","DONE","ERROR"};
        return type>=0 && type<names.length ? names[type] : "UNKNOWN";
    }
}
