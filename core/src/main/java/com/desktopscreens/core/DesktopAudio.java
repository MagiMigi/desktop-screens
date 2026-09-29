package com.desktopscreens.core;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The sound of a shared screen: what this PC plays, recorded program by program ({@link Wasapi.Loopback}) and mixed
 * into 20 ms stereo frames at 48 kHz, for the Opus encoder. Runs on its own thread until {@link #close}.
 *
 * <p>What it records: one program, when the screen shows one window (its program and whatever that started), or else
 * every program with sound except Minecraft itself and the programs on the skip list: voice chat, where a watcher on
 * the same call would hear themselves again, audio routers (NVIDIA Broadcast, Voicemeeter...), which play every
 * program's sound a second time, Minecraft's and the call's included, and other Minecraft games on this PC (the list
 * is the player's, in their config). Windows can record "everything but one program"
 * but not "everything but these", hence one recording per program. A program is also left out when it started a
 * skipped one or this game (recording it would include them), or was started by a skipped one (Teams' and WhatsApp's
 * sound comes from Edge helper processes they start). The choice is made again every second, so programs that start
 * playing later join in.
 */
public final class DesktopAudio {
    public static final int RATE = Wasapi.RATE, CHANNELS = Wasapi.CHANNELS;
    /** Samples per channel in one frame: 20 ms, which Opus takes as it is. */
    public static final int FRAME = RATE / 50;

    public interface Sink {
        /**
         * One frame, {@link #FRAME} stereo samples interleaved, and about when its first sample played on this PC
         * ({@link System#nanoTime}, within a packet's 10 ms). On the audio thread; the array is reused.
         */
        void frame(short[] pcm, long playedAt);
    }

    /** Where to report which programs are recorded, and problems. Called on the audio thread. */
    public interface Log {
        void log(String message);
    }

    private static final long PLAN_NANOS = 1000000000L;
    private static final int WAIT_MILLIS = 20;
    /** Windows waits for at most 64 events at once (MAXIMUM_WAIT_OBJECTS). */
    private static final int MAX_SOURCES = 64;
    private static final int FLOATS = FRAME * CHANNELS;

    private final Sink sink;
    private final Log log;
    private final List<String> skip;
    /** The program of the window shown (0: everything but the skipped ones). */
    private volatile int program;
    private volatile boolean running = true;

    /** Starts recording. {@code skip}: lower-case beginnings of program names to leave out. */
    public DesktopAudio(Sink sink, List<String> skip, Log log) {
        this.sink = sink;
        this.skip = new ArrayList<String>(skip);
        this.log = log;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "Desktop Screens sound");
        thread.setDaemon(true);
        thread.start();
    }

    /** Only this program's sound (the process id of the window shown), or 0 for everything but the skipped programs. */
    public void setProgram(int pid) {
        program = pid;
    }

    public void close() {
        running = false;
    }

    /** One program's recording and the sound from it that isn't mixed yet. */
    private static final class Source {
        final Wasapi.Loopback loopback;
        float[] waiting = new float[FLOATS * 16];
        int level; // floats in waiting

        Source(Wasapi.Loopback loopback) {
            this.loopback = loopback;
        }

        final Wasapi.Loopback.Packets packets = new Wasapi.Loopback.Packets() {
            @Override
            public void accept(Pointer data, int frames) {
                int floats = frames * CHANNELS;
                if (floats > waiting.length) return; // never seen: a packet is 10 ms
                if (level + floats > waiting.length) { // falling behind: the oldest goes
                    int drop = level + floats - waiting.length;
                    System.arraycopy(waiting, drop, waiting, 0, level - drop);
                    level -= drop;
                }
                if (data == null) Arrays.fill(waiting, level, level + floats, 0f);
                else data.read(0, waiting, level, floats);
                level += floats;
            }
        };

        /** Adds up to one frame of it to {@code mix}. */
        void takeInto(float[] mix) {
            int n = Math.min(level, FLOATS);
            for (int i = 0; i < n; i++) mix[i] += waiting[i];
            System.arraycopy(waiting, n, waiting, 0, level - n);
            level -= n;
        }
    }

    private void loop() {
        List<Source> sources = new ArrayList<Source>();
        Memory events = null;
        float[] mix = new float[FLOATS];
        short[] pcm = new short[FLOATS];
        long plannedAt = 0;
        int plannedFor = -1;
        String described = null;
        try {
            Wasapi.start();
            while (running) {
                long now = System.nanoTime();
                int wanted = program;
                if (now - plannedAt > PLAN_NANOS || wanted != plannedFor) {
                    plannedAt = now;
                    plannedFor = wanted;
                    Processes processes = new Processes();
                    Set<Integer> pids = choose(processes, wanted);
                    String description = update(sources, pids, processes);
                    if (!description.equals(described)) {
                        described = description;
                        log.log(description);
                    }
                    events = sources.isEmpty() ? null : new Memory((long) Native.POINTER_SIZE * sources.size());
                    for (int i = 0; i < sources.size(); i++) events.setPointer((long) i * Native.POINTER_SIZE, sources.get(i).loopback.event());
                }
                if (events == null) {
                    Thread.sleep(WAIT_MILLIS); // nothing to record: nothing to send either
                    continue;
                }
                Wasapi.waitForAny(events, sources.size(), WAIT_MILLIS);
                boolean lost = false;
                for (Iterator<Source> it = sources.iterator(); it.hasNext(); ) {
                    Source s = it.next();
                    try {
                        s.loopback.drain(s.packets);
                    } catch (RuntimeException e) { // the program closed, say
                        s.loopback.close();
                        it.remove();
                        lost = true;
                    }
                }
                if (lost) plannedAt = 0; // choose again right away
                mixFrames(sources, mix, pcm);
            }
        } catch (InterruptedException ignored) {
            // closing
        } catch (RuntimeException | LinkageError e) {
            log.log("Sound: stopped recording: " + e);
        } finally {
            for (Source s : sources) s.loopback.close();
        }
    }

    /**
     * Mixes and hands over whole frames: once every recording has one, or once one of them is 3 frames ahead (a
     * program that stopped delivering; it gets silence instead). All of them are paced by the same audio engine, so
     * normally they line up exactly.
     */
    private void mixFrames(List<Source> sources, float[] mix, short[] pcm) {
        long now = System.nanoTime(); // just after draining: the newest sample waiting played about now
        while (true) {
            int least = Integer.MAX_VALUE, most = 0;
            for (Source s : sources) {
                least = Math.min(least, s.level);
                most = Math.max(most, s.level);
            }
            if (sources.isEmpty() || least < FLOATS && most < 3 * FLOATS) return;
            long playedAt = now - (long) (most / CHANNELS) * 1000000000L / RATE;
            Arrays.fill(mix, 0f);
            for (Source s : sources) s.takeInto(mix);
            for (int i = 0; i < FLOATS; i++) {
                float v = mix[i] * 32767f;
                pcm[i] = (short) (v >= 32767f ? 32767 : v <= -32768f ? -32768 : (int) v);
            }
            sink.frame(pcm, playedAt);
        }
    }

    /** The programs to record now: see the class comment. */
    private Set<Integer> choose(Processes processes, int wanted) {
        Set<Integer> chosen = new HashSet<Integer>();
        int me = Kernel32.INSTANCE.GetCurrentProcessId();
        List<Integer> mine = processes.selfAndParents(me);
        if (wanted != 0) {
            // One program, even a skipped one: the player picked its window. Not one that started this game, though.
            if (!mine.contains(wanted) && processes.all().contains(wanted)) chosen.add(wanted);
            return chosen;
        }
        Set<Integer> excluded = new HashSet<Integer>(mine);
        for (int pid : processes.all()) {
            if (processes.nameStartsWith(pid, skip)) excluded.addAll(processes.selfAndParents(pid));
        }
        List<Integer> candidates = new ArrayList<Integer>();
        for (int pid : Wasapi.sessionProcesses()) {
            if (excluded.contains(pid)) continue;
            boolean startedBySkipped = false;
            for (int parent : processes.selfAndParents(pid)) {
                if (parent == me || processes.nameStartsWith(parent, skip)) startedBySkipped = true;
            }
            if (!startedBySkipped) candidates.add(pid);
        }
        // A program whose starter is recorded too is in that recording already.
        for (int pid : candidates) {
            boolean inside = false;
            for (int parent : processes.selfAndParents(pid)) {
                if (parent != pid && candidates.contains(parent)) inside = true;
            }
            if (!inside) chosen.add(pid);
        }
        return chosen;
    }

    /** Opens and closes recordings to match {@code pids}; says what's recorded now. */
    private String update(List<Source> sources, Set<Integer> pids, Processes processes) {
        for (Iterator<Source> it = sources.iterator(); it.hasNext(); ) {
            Source s = it.next();
            if (!pids.contains(s.loopback.pid)) {
                s.loopback.close();
                it.remove();
            }
        }
        StringBuilder failed = new StringBuilder();
        for (int pid : pids) {
            boolean open = false;
            for (Source s : sources) open |= s.loopback.pid == pid;
            if (open || sources.size() >= MAX_SOURCES) continue;
            try {
                Source added = new Source(new Wasapi.Loopback(pid));
                // Starts in step with the others: as far along as the least filled one.
                int least = Integer.MAX_VALUE;
                for (Source s : sources) least = Math.min(least, s.level);
                if (least != Integer.MAX_VALUE) added.level = Math.min(least, added.waiting.length);
                sources.add(added);
            } catch (RuntimeException e) {
                failed.append(failed.length() == 0 ? "" : ", ").append(processes.name(pid)).append(" (").append(e.getMessage()).append(')');
            }
        }
        List<String> names = new ArrayList<String>();
        for (Source s : sources) names.add(processes.name(s.loopback.pid));
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        String what = names.isEmpty() ? "Sound: no program to record" : "Sound: recording " + join(names);
        return failed.length() == 0 ? what : what + "; couldn't record " + failed;
    }

    private static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append(sb.length() == 0 ? "" : ", ").append(n);
        return sb.toString();
    }
}
