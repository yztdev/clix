import java.lang.reflect.*;

public class SigProbe {
    public static void main(String[] args) throws Exception {
        try {
            Class<?> sh = Class.forName("sun.misc.SignalHandler");
            Class<?> sg = Class.forName("sun.misc.Signal");
            Object handler = Proxy.newProxyInstance(SigProbe.class.getClassLoader(),
                    new Class<?>[]{sh}, (p, m, a) -> null);
            Object sig = sg.getConstructor(String.class).newInstance("INT");
            Object prev = sg.getMethod("handle", sg, sh).invoke(null, sig, handler);
            System.out.println("INT handled OK, previous=" + prev);
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null) c = c.getCause();
            System.out.println("INT handle FAILED: " + c);
        }
    }
}
