=== 无 type hint — 反编译 Java ===

// Decompiling class: gen_perf_artifacts$sum_no_hint
import clojure.lang.*;

public final class gen_perf_artifacts$sum_no_hint extends AFunction
{
    public static final Var __reduce;
    public static final Var ___PLUS_;
    public static final Var __map;
    public static final Var __range;
    
    public static Object invokeStatic(final Object n) {
        return __reduce.invoke(___PLUS_.getRawRoot(), __map.invoke(new gen_perf_artifacts$sum_no_hint$fn__943(), __range.invoke(n)));
    }
    
    @Override
    public Object invoke(final Object n) {
        return invokeStatic(n);
    }
    
    static {
        __reduce = RT.var("clojure.core", "reduce");
        ___PLUS_ = RT.var("clojure.core", "+");
        __map = RT.var("clojure.core", "map");
        __range = RT.var("clojure.core", "range");
    }
}


// Decompiling class: gen_perf_artifacts$sum_no_hint$fn__943
import clojure.lang.*;

public final class gen_perf_artifacts$sum_no_hint$fn__943 extends AFunction
{
    @Override
    public Object invoke(final Object p1__940_SHARP_) {
        final double doubleCast = RT.doubleCast(p1__940_SHARP_);
        this = null;
        return Math.sqrt(doubleCast);
    }
}


=== 有 type hint — 反编译 Java ===

// Decompiling class: gen_perf_artifacts$sum_hint
import clojure.lang.*;

public final class gen_perf_artifacts$sum_hint extends AFunction implements LO
{
    public static Object invokeStatic(final long n) {
        long i = 0L;
        double sum = 0.0;
        while (i < n) {
            final long n2 = i + 1L;
            sum += Math.sqrt((double)i);
            i = n2;
        }
        return sum;
    }
    
    @Override
    public Object invoke(final Object o) {
        return invokeStatic(RT.longCast(o));
    }
    
    @Override
    public final Object invokePrim(final long n) {
        return invokeStatic(n);
    }
}

