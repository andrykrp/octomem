import java.lang.instrument.Instrumentation;

/** Test-only shallow layout measurement; never included in server artifacts. */
public final class SizeAgent {
    static Instrumentation instrumentation;

    /** Receives standard premain instrumentation from the launcher. */
    public static void premain(String options, Instrumentation value) {
        instrumentation = value;
    }
}
