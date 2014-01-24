package <your package>;

import java.util.Locale;
import java.util.Stack;
import android.annotation.SuppressLint;
import android.text.TextUtils;

/**
 * Time-logger to make basic performance analysis a bit easier. Logs to android's Log.d.<br />
 * Use static methods to use a system-wide shared logger, or make your own instance (pass a name!) to do more
 * fine-grained behavior like per-thread timing, without affecting the shared logger.
 * <p>
 * Not really intended to be used un-modified / to fit all needs, just intended to be usable with a single file.
 * <p>
 * Basic internal behavior is as follows:
 * <ul>
 * <li>logging sets the 'last time' value on the current (top of stack) TimeData instance.</li>
 * <li>beginning a block pushes a new TimeData instance onto the stack.</li>
 * <li>ending a block pops the top TimeData instance, and updates the new-top's 'last time' value so subsequent logs are
 * based on the end of the block.</li>
 * <li>'unaccounted' time is time between the last log and the end of a block, intended to highlight if you have
 * unexpectedly-costly calculations that do not have finer-grained log statements.</li>
 * </ul>
 *
 * @author groxx
 */
public class Tim {
    private static final String DEFAULT_TAG = "Tim";

    private final Stack<TimeData> times = new Stack<TimeData>();
    private final String tag;

    /** Creates a Tim logger with the default tag: "Tim" */
    public Tim() { this(DEFAULT_TAG); }

    /**
     * Creates a Tim logger with the passed tag.<br />
     * This is NOT prefixed - for easier grepping consider prefixing with e.g. "Tim " so you can show all Tim logs at
     * once.
     */
    public Tim(String name) {
        tag = name;
        // Add a 'bottom' TimeData, so the stack is never empty, but is never displayed. Simplifies things.
        times.push(new TimeData("<root>", now()));
    }

    /**
     * Log a message. Will show elapsed time since last log call.
     *
     * @param text to log
     */
    public synchronized void log(String text) {
        long now = now();
        TimeData d = times.peek();
        _log(text + elapsed(d.lastTime, now));
        d.lastTime = now;
    }

    /**
     * Begin a timed block with a name. All internal logs will be indented, and the block will print run-time
     * information when ended.
     *
     * @param name of the logging-block
     */
    public synchronized void begin(String name) {
        begin(name, null);
    }

    /**
     * Begin a timed block with a name, and also display a message which is not part of the name. All internal logs will
     * be indented, and the block will print run-time information when ended.
     *
     * @param name of the logging-block
     * @param message optional message to display, not considered part of the block name.
     */
    public synchronized void begin(String name, String message) {
        long now = now();
        _log("Beginning " + name + (message == null ? "" : ": " + message) + elapsed(times.peek().lastTime, now));
        times.push(new TimeData(name, now));
    }

    /**
     * End the current timed block. Since this could end *any* timed block if e.g. you do not wrap your logging in
     * try/finally, you probably want to use {@link #end(String)} with an explicit block name. This is simpler though.
     */
    public synchronized void end() {
        long now = now();
        if (times.size() == 1) {
            log("!!! Could not end, already at bottom !!!");
        } else {
            TimeData d = times.pop();
            String blockName = d.name == null ? "" : " " + d.name;
            _log("Ended" + blockName + elapsed(d.startTime, now) + unaccounted(d.startTime, d.lastTime, now));
            times.peek().lastTime = now;
        }
    }

    /**
     * End a named timed block, also printing run-time and unaccounted time.<br />
     * Will pop 'down' to the name if there are any intermediate un-ended blocks, and will log (without modifying
     * existing blocks) if it was not found. Due to the more reliable behavior, this is generally preferred over
     * {@link #end()}.
     *
     * @param name block to end
     */
    public synchronized void end(String name) {
        TimeData[] stack = new TimeData[times.size()];
        stack = times.toArray(stack);

        int height = 0;
        boolean found = false;
        for (int i = stack.length - 1; i >= 0; i--) {
            height++;
            if (TextUtils.equals(name, stack[i].name)) {
                found = true;
                break;
            }
        }

        if (!found) {
            log("Unknown end: " + name);
        } else {
            // pop down to the known element
            while (height-- > 0) {
                if (height > 0) {
                    // using private log because we do not care about elapsed time here.
                    // 'missing' time since last log will be reported as unaccounted time, which is probably more accurate.
                    _log("<forced end, run time inaccurate>");
                }
                end();
            }
        }
    }

    /**
     * Private log-helper with tag and current indentation. Does no other work.<br />
     * Callers should add their own timing information.
     */
    private void _log(String text) {
        android.util.Log.d(tag, pad() + text);
    }

    /**
     * Returns a "now" time for comparison with others. NOT intended to be used to display wall-clock time.
     *
     * @return nanoTime
     */
    private static long now() {
        return System.nanoTime();
    }

    /**
     * @param start time this block started, or time since last log
     * @param now current 'now' time
     * @return time string since last log, padded for easy appending. e.g. " 5 ms"
     * @see #format(long) for more precise formatting description.
     */
    private static String elapsed(long start, long now) {
        long diff = now - start;
        return " " + format(diff);
    }

    /**
     * Calculate not-attributed time since last log. Helps point out extensive otherwise-hidden calculations.
     *
     * @param start time this block started
     * @param last time of last log
     * @param now current 'now' time
     * @return time string displaying un-annotated time since last log, if any, padded for easy appending.  e.g. " (unaccounted: 12 ms)"
     * @see #format(long) for more precise formatting description.
     */
    private static String unaccounted(long start, long last, long now) {
        if (start == last) {
            // no inner logs, ignore
            return "";
        }
        return " (unaccounted: " + format(now - last) + ")";
    }

    /**
     * @param nanos
     * @return "5 ms" or "0.23 ms" as is appropriate.
     */
    @SuppressLint("DefaultLocale")
    private static String format(long nanos) {
        final String result;
        if (nanos >= 1000000 * 10) {
            result = String.format(Locale.getDefault(), "%d ms", nanos / 1000000);
        } else {
            result = String.format(Locale.getDefault(), "%.2f ms", Float.valueOf(nanos) / 1000000F);
        }
        return result;
    }

    /**
     * @return padding for the current indentation level
     */
    private String pad() {
        StringBuilder sb = new StringBuilder();
        for (int i = times.size() - 1; i > 0; i--) {
            sb.append("  ");
        }
        return sb.toString();
    }

    /**
     * Lazy-initialized shared static version of the logger. Just use capital letters to access it.
     */
    private static class LazyTim {
        public final static Tim SHARED = new Tim();
    }

    /**
     * Log a message. Displays time since last message was displayed.
     *
     * @see #log(String) for more details and an instance-specific version.
     * @warning the very-first call to this, if not preceeded by {@link #Begin(String)}, will show elapsed time since
     *          the lazily-initialized shared Tim-logger was created. This depends entirely on class-loading behavior
     *          and is only useful for showing just how lazy it is, but since this is somewhat interesting it is left.
     */
    public static void Log(String body) {
        LazyTim.SHARED.log(body);
    }

    /**
     * Begin a timed block with a name.
     *
     * @see #begin(String) for more details and an instance-specific version.
     */
    public static void Begin(String name) {
        LazyTim.SHARED.begin(name);
    }

    /**
     * Begin a timed block with a name, and an additional message to display.
     *
     * @see #begin(String, String) for more details and an instance-specific version.
     */
    public static void Begin(String name, String message) {
        LazyTim.SHARED.begin(name, message);
    }

    /**
     * End any timed block. Use {@link #End(String)} when possible to avoid attribution errors.
     *
     * @see #end() for more details and an instance-specific version.
     */
    public static void End() {
        LazyTim.SHARED.end();
    }

    /**
     * End the named timed block. Blocks will be removed until it is found.
     *
     * @see #end(String) for more details and an instance-specific version.
     */
    public static void End(String name) {
        LazyTim.SHARED.end(name);
    }

    /**
     * Timed-block data-holder class, so elapsed time can be tracked.
     */
    private static class TimeData {
        final String name;
        long startTime;
        long lastTime;

        public TimeData(String name, long time) {
            this.name = name;
            this.lastTime = this.startTime = time;
        }

        /**
         * Shows data's block name, maybe useful when peeking at the stack.
         */
        public String toString() {
            return name == null ? "<no name>" : name;
        }
    }
}
