package ru.octomem.transport;

/** Creates independent handlers on their owning event loop, never on the acceptor. */
@FunctionalInterface
public interface ConnectionFactory {
    /**
     * Creates a handler; it must not block or retain another connection's state.
     * On failure the factory cleans its partially constructed handler resources.
     * Protocol implementations must receive budget adapters, not import transport.
     * @param resources loop-confined admission hooks; transport owns line admission
     * @return non-null handler owned by the transport after successful return
     * @throws Exception if initialization fails; only this connection is closed
     */
    ConnectionHandler create(ConnectionResources resources) throws Exception;
    /** Additional fixed per-connection control allowance admitted before construction. */
    default long additionalControlBytes(){return 0;}
}
