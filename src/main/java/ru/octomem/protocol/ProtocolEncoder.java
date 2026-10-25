package ru.octomem.protocol;

/** Creates resumable writers on the connection event loop.
 * @since 1.0
 */
public interface ProtocolEncoder {
    /**
     * Encodes a response once, without materializing all bytes or copying values.
     * Successful return transfers lifecycle ownership to the writer; failure
     * requires the caller to close the response. No bytes precede snapshot admission.
     * @param response non-null open response owned by the caller
     * @return non-null resumable writer owning response cleanup
     * @throws NullPointerException if response is null
     */
    ResponseWriter encode(Response response);
}
