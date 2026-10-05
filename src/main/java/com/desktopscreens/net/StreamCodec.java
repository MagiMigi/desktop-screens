package com.desktopscreens.net;

import com.mojang.datafixers.util.Function3;
import com.mojang.datafixers.util.Function4;
import com.mojang.datafixers.util.Function5;
import com.mojang.datafixers.util.Function6;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * For Minecraft before 1.20.5: the parts of its later {@code StreamCodec} our messages use, with the same names, so
 * their code is the same in every version. Writes a value to a buffer and reads it back.
 */
public interface StreamCodec<B, V> {
    V decode(B buf);

    void encode(B buf, V value);

    static <B, V> StreamCodec<B, V> of(BiConsumer<B, V> encoder, Function<B, V> decoder) {
        return new StreamCodec<>() {
            @Override
            public V decode(B buf) {
                return decoder.apply(buf);
            }

            @Override
            public void encode(B buf, V value) {
                encoder.accept(buf, value);
            }
        };
    }

    /** Nothing on the wire: always {@code value}. */
    static <B, V> StreamCodec<B, V> unit(V value) {
        return of((buf, v) -> {}, buf -> value);
    }

    default <O> StreamCodec<B, O> map(Function<? super V, ? extends O> to, Function<? super O, ? extends V> from) {
        StreamCodec<B, V> self = this;
        return of((buf, o) -> self.encode(buf, from.apply(o)), buf -> to.apply(self.decode(buf)));
    }

    default <O> StreamCodec<B, O> apply(CodecOperation<B, V, O> operation) {
        return operation.apply(this);
    }

    interface CodecOperation<B, S, T> {
        StreamCodec<B, T> apply(StreamCodec<B, S> codec);
    }

    static <B, C, T1, T2> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1, Function<C, T1> g1,
            StreamCodec<? super B, T2> c2, Function<C, T2> g2,
            BiFunction<T1, T2, C> factory) {
        return of((buf, v) -> {
            c1.encode(buf, g1.apply(v));
            c2.encode(buf, g2.apply(v));
        }, buf -> factory.apply(c1.decode(buf), c2.decode(buf)));
    }

    static <B, C, T1, T2, T3> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1, Function<C, T1> g1,
            StreamCodec<? super B, T2> c2, Function<C, T2> g2,
            StreamCodec<? super B, T3> c3, Function<C, T3> g3,
            Function3<T1, T2, T3, C> factory) {
        return of((buf, v) -> {
            c1.encode(buf, g1.apply(v));
            c2.encode(buf, g2.apply(v));
            c3.encode(buf, g3.apply(v));
        }, buf -> factory.apply(c1.decode(buf), c2.decode(buf), c3.decode(buf)));
    }

    static <B, C, T1, T2, T3, T4> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1, Function<C, T1> g1,
            StreamCodec<? super B, T2> c2, Function<C, T2> g2,
            StreamCodec<? super B, T3> c3, Function<C, T3> g3,
            StreamCodec<? super B, T4> c4, Function<C, T4> g4,
            Function4<T1, T2, T3, T4, C> factory) {
        return of((buf, v) -> {
            c1.encode(buf, g1.apply(v));
            c2.encode(buf, g2.apply(v));
            c3.encode(buf, g3.apply(v));
            c4.encode(buf, g4.apply(v));
        }, buf -> factory.apply(c1.decode(buf), c2.decode(buf), c3.decode(buf), c4.decode(buf)));
    }

    static <B, C, T1, T2, T3, T4, T5> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1, Function<C, T1> g1,
            StreamCodec<? super B, T2> c2, Function<C, T2> g2,
            StreamCodec<? super B, T3> c3, Function<C, T3> g3,
            StreamCodec<? super B, T4> c4, Function<C, T4> g4,
            StreamCodec<? super B, T5> c5, Function<C, T5> g5,
            Function5<T1, T2, T3, T4, T5, C> factory) {
        return of((buf, v) -> {
            c1.encode(buf, g1.apply(v));
            c2.encode(buf, g2.apply(v));
            c3.encode(buf, g3.apply(v));
            c4.encode(buf, g4.apply(v));
            c5.encode(buf, g5.apply(v));
        }, buf -> factory.apply(c1.decode(buf), c2.decode(buf), c3.decode(buf), c4.decode(buf), c5.decode(buf)));
    }

    static <B, C, T1, T2, T3, T4, T5, T6> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1, Function<C, T1> g1,
            StreamCodec<? super B, T2> c2, Function<C, T2> g2,
            StreamCodec<? super B, T3> c3, Function<C, T3> g3,
            StreamCodec<? super B, T4> c4, Function<C, T4> g4,
            StreamCodec<? super B, T5> c5, Function<C, T5> g5,
            StreamCodec<? super B, T6> c6, Function<C, T6> g6,
            Function6<T1, T2, T3, T4, T5, T6, C> factory) {
        return of((buf, v) -> {
            c1.encode(buf, g1.apply(v));
            c2.encode(buf, g2.apply(v));
            c3.encode(buf, g3.apply(v));
            c4.encode(buf, g4.apply(v));
            c5.encode(buf, g5.apply(v));
            c6.encode(buf, g6.apply(v));
        }, buf -> factory.apply(c1.decode(buf), c2.decode(buf), c3.decode(buf), c4.decode(buf), c5.decode(buf), c6.decode(buf)));
    }
}
