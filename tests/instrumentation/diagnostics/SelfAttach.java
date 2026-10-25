import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
public final class SelfAttach {
    public static void main(String[] a) throws Exception {
        String exe = System.getProperty("java.home") + "\\bin\\jcmd.exe";
        long pid = ProcessHandle.current().pid();
        System.out.println("pid=" + pid);
        Process p = new ProcessBuilder(exe, Long.toString(pid), "GC.class_histogram")
                .redirectErrorStream(true).start();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { }
            } catch (Exception ignored) { }
        });
        t.setDaemon(true); t.start();
        boolean done = p.waitFor(20, TimeUnit.SECONDS);
        System.out.println("waitForReturned=" + done);
        if (!done) { p.destroyForcibly(); System.out.println("DEADLOCK-CONFIRMED"); }
    }
}
