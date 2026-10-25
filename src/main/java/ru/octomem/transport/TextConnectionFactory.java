package ru.octomem.transport;

import java.util.Objects;
import java.util.function.Function;
import ru.octomem.protocol.Command;
import ru.octomem.protocol.ProtocolBudget;
import ru.octomem.protocol.ProtocolEncoder;
import ru.octomem.protocol.Response;
import ru.octomem.protocol.TextProtocolDecoder;
import ru.octomem.engine.StorageAdmission;

/**
 * Immutable factory connecting the text codec to transport-owned admission.
 * The supplied dispatcher and encoder are shared across event loops and must be
 * thread-safe and nonblocking; a stateless encoder is recommended. Writers and
 * returned responses must have independent, connection-confined mutable state.
 * Neither shared dependency is closed by this factory or its handlers.
 */
public final class TextConnectionFactory implements ConnectionFactory {
    private final int maxLineBytes;
    private final int itemMaxSize;
    private final Function<Command, Response> dispatcher;
    private final ProtocolEncoder encoder;
    private final StorageAdmission admission;

    /**
     * Creates a factory without allocating any per-connection codec state.
     * @param maxLineBytes positive line limit, excluding terminator
     * @param itemMaxSize positive complete aligned item limit
     * @param dispatcher non-null synchronous command executor; null responses suppress output
     * @param encoder non-null shared normal-response encoder
     */
    public TextConnectionFactory(int maxLineBytes, int itemMaxSize,
            Function<Command, Response> dispatcher, ProtocolEncoder encoder) {
        this(maxLineBytes,itemMaxSize,dispatcher,encoder,null);
    }

    /** Adds real header admission while preserving legacy factory behavior. */
    public TextConnectionFactory(int maxLineBytes, int itemMaxSize,
            Function<Command, Response> dispatcher, ProtocolEncoder encoder, StorageAdmission admission) {
        if (maxLineBytes <= 0 || itemMaxSize <= 0) {
            throw new IllegalArgumentException("Limits must be positive");
        }
        this.maxLineBytes = maxLineBytes;
        this.itemMaxSize = itemMaxSize;
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.admission=admission;
    }

    @Override public TextConnectionHandler create(ConnectionResources resources) {
        Objects.requireNonNull(resources, "resources");
        TextProtocolDecoder decoder = new TextProtocolDecoder(maxLineBytes, itemMaxSize, new BodyBudget(resources), admission);
        return new TextConnectionHandler(decoder, dispatcher, encoder);
    }

    private record BodyBudget(ConnectionResources resources) implements ProtocolBudget {
        @Override public boolean tryReserveBody(int payloadBytes) { return resources.tryReserveBody(payloadBytes); }
        @Override public void releaseBody(int payloadBytes) { resources.releaseBody(payloadBytes); }
    }
}
