package ru.octomem.server;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import ru.octomem.cli.CacheServer;
import ru.octomem.config.ConfigLoader;
import ru.octomem.protocol.Command;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.StoredItem;
import ru.octomem.transport.ConnectionFactory;
import ru.octomem.transport.ConnectionResources;
import ru.octomem.transport.NioTransport;
import ru.octomem.util.Clock;

/** Standard Instrumentation probe, no Unsafe/JOL or product measurement hooks. */
public final class ExpandedLayoutProbe {
    private static Instrumentation agent;
    public static void premain(String args,Instrumentation instrumentation){agent=instrumentation;}
    private static Object field(Object object,String name)throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static long size(Object root,Set<Object> excluded,Set<Object> seen)throws Exception {
        if(root==null||excluded.contains(root)||!seen.add(root)||root instanceof Enum<?>||root instanceof Class<?>)return 0;
        long bytes=agent.getObjectSize(root);Class<?> type=root.getClass();
        if(type.isArray()) {if(!type.componentType().isPrimitive())for(int i=0;i<Array.getLength(root);i++)bytes+=size(Array.get(root,i),excluded,seen);return bytes;}
        if(root instanceof ByteBuffer buffer){if(buffer.hasArray())bytes+=size(buffer.array(),excluded,seen);return bytes;}
        if(root instanceof String){Field value=String.class.getDeclaredField("value");value.setAccessible(true);return bytes+size(value.get(root),excluded,seen);}
        if(!type.getName().startsWith("ru.octomem"))return bytes;
        for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Field f:c.getDeclaredFields()){
            if(Modifier.isStatic(f.getModifiers())||f.getType().isPrimitive())continue;f.setAccessible(true);bytes+=size(f.get(root),excluded,seen);
        }
        return bytes;
    }
    private static Set<Object> identities(){return Collections.newSetFromMap(new IdentityHashMap<>());}
    public static void main(String[] args)throws Exception {
        if(agent==null)throw new IllegalStateException("javaagent required");
        System.out.println("java="+System.getProperty("java.runtime.version")+" vendor="+System.getProperty("java.vendor"));
        Clock clock=new Clock(){public long currentTimeSeconds(){return 1700000000;}public long nanoTime(){return 0;}};
        var config=ConfigLoader.load(null,Map.of("transport.workers","1","memory.limit","4194304","item.maxSize","4096"));
        CacheServer server=new CacheServer(config,new InetSocketAddress("127.0.0.1",0),clock);
        try {
            Object transport=field(server,"transport");Field f=NioTransport.class.getDeclaredField("factory");f.setAccessible(true);
            ConnectionFactory factory=(ConnectionFactory)f.get(transport);
            var constructor=ConnectionResources.class.getDeclaredConstructor(AtomicLong.class,long.class,int.class);constructor.setAccessible(true);
            try(var resources=constructor.newInstance(new AtomicLong(),65536L,4096)) {
                var handler=factory.create(resources);Set<Object> excluded=identities();excluded.add(factory);excluded.add(resources);
                for(String name:new String[]{"engine","dispatcher","classic"})excluded.add(field(factory,name));
                long additional=factory.additionalControlBytes();
                long idle=size(handler,excluded,identities());System.out.println("idleHandler="+idle+" additionalControl="+additional);
                if(idle>additional)throw new AssertionError("idle handler exceeds additional control allowance");
                try {
                    String request="mg key O"+"o".repeat(32)+" P"+"p".repeat(8000)+" Lroute v c\r\n";
                    handler.decoder().append(ByteBuffer.wrap(ExpandedWire.ascii(request)));
                    Command command=handler.decoder().nextCommand();if(command==null)throw new AssertionError("complete meta frame not decoded");
                    long active=size(handler,excluded,identities());
                    // Existing lazy line allowance plus new per-connection control allowance.
                    long charge=16640+additional;
                    System.out.println("activeHandlerCommandGraph="+active+" linePlusAdditional="+charge);
                    if(active>charge)throw new AssertionError("retained frame graph exceeds charged envelopes");
                } finally {handler.close();}
                long released=size(handler,excluded,identities());System.out.println("closedHandlerGraph="+released);
            }
        } finally {server.close();}
        try(var allocator=new FfmSlabAllocator(65536,4096,4096)) {
            StoredItem item=StoredItem.create(allocator,new byte[]{1},new byte[]{2},7,0,100,4096);
            if(item==null)throw new AssertionError("record fixture");
            try {
                Set<Object> excluded=identities();excluded.add(allocator);
                // The slab page/arena/free-slot map is shared and separately charged
                // by allocator page metadata, not owned once per StoredItem.
                Object handle=field(item,"handle");Object page=field(handle,"page");excluded.add(page);
                System.out.println("excludedSharedPageShallow="+agent.getObjectSize(page));
                long record=size(item,excluded,identities());long charge=StoredItem.ServiceBudget.CHARGE;
                System.out.println("storedItemOwnedHeap="+record+" serviceCharge="+charge);
                if(record>charge)throw new AssertionError("record metadata exceeds unchanged service charge");
            } finally {item.retire();}
            if(allocator.usedMemory()!=0)throw new AssertionError("native leak");
        }
    }
}
