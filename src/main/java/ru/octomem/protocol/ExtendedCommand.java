package ru.octomem.protocol;

import ru.octomem.engine.ExtendedRequest;
import ru.octomem.engine.KeySequence;
import ru.octomem.engine.KeyVisitor;

/** One bounded, connection-owned binary or meta frame. Never retained by the engine. */
public final class ExtendedCommand implements Command {
    public final ExtendedRequest request=new ExtendedRequest();
    public boolean binary;
    public int opcode;
    public int opaque;
    public String meta;
    public String[] options;
    public int error;
    public String errorText;
    public boolean quiet;
    public boolean close;
    public int bodyLength;
    public int bodyReserved=-1;
    public byte[] payload;
    public String stats;
    @Override public CommandType type() { return CommandType.VERSION; }
    @Override public KeySequence keys() { return new KeySequence() {
        public int size() { return request.key==null?0:1; }
        public void forEachKey(KeyVisitor visitor) { if(request.key!=null) visitor.accept(request.key,0,request.key.length); }
    }; }
    @Override public int flags() { return request.flags; }
    @Override public long exptime() { return request.ttl; }
    @Override public byte[] value() { return payload; }
    @Override public long casId() { return request.expectedCas; }
    @Override public long delta() { return request.delta; }
    @Override public int level() { return 0; }
    @Override public boolean noreply() { return false; }
}
