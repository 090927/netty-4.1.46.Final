/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util.internal;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The internal data structure that stores the thread-local variables for Netty and all {@link FastThreadLocal}s.
 * Note that this class is for internal use only and is subject to change at any time.  Use {@link FastThreadLocal}
 * unless you know what you are doing.
 */
public final class InternalThreadLocalMap extends UnpaddedInternalThreadLocalMap {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(InternalThreadLocalMap.class);

    private static final int DEFAULT_ARRAY_LIST_INITIAL_CAPACITY = 8;
    private static final int STRING_BUILDER_INITIAL_SIZE;
    private static final int STRING_BUILDER_MAX_SIZE;

    public static final Object UNSET = new Object();

    private BitSet cleanerFlags;

    static {
        STRING_BUILDER_INITIAL_SIZE =
                SystemPropertyUtil.getInt("io.netty.threadLocalMap.stringBuilder.initialSize", 1024);
        logger.debug("-Dio.netty.threadLocalMap.stringBuilder.initialSize: {}", STRING_BUILDER_INITIAL_SIZE);

        STRING_BUILDER_MAX_SIZE = SystemPropertyUtil.getInt("io.netty.threadLocalMap.stringBuilder.maxSize", 1024 * 4);
        logger.debug("-Dio.netty.threadLocalMap.stringBuilder.maxSize: {}", STRING_BUILDER_MAX_SIZE);
    }

    public static InternalThreadLocalMap getIfSet() {
        Thread thread = Thread.currentThread();
        if (thread instanceof FastThreadLocalThread) {
            return ((FastThreadLocalThread) thread).threadLocalMap();
        }
        return slowThreadLocalMap.get();
    }

    public static InternalThreadLocalMap get() {
        Thread thread = Thread.currentThread();

        /**
         * 判断，当前线程是否 `FastThreadLocalThread`
         */
        if (thread instanceof FastThreadLocalThread) {

            /**
             *  获取 FastThreadLocalThread 的 threadLocalMap 属性 {@link #fastGet(FastThreadLocalThread)}
             */
            return fastGet((FastThreadLocalThread) thread);
        } else {

            /**
             *  JDK 原生 ThreadLocal 中获取 InternalThreadLocalMap {@link #slowGet()}
             */
            return slowGet();
        }
    }

    private static InternalThreadLocalMap fastGet(FastThreadLocalThread thread) {
        /**
         * FastThreadLocalThread 内部维护一个 {@link InternalThreadLocalMap}
         *
         * 1、new InternalThreadLocalMap()
         *    1、创建一个 长度为32的数组，并将数组中的每一个对象都设置为UNSET，UNSET是一个Object的对象，表示该下标的值没有被设置
         *
         */
        InternalThreadLocalMap threadLocalMap = thread.threadLocalMap();
        if (threadLocalMap == null) {
            thread.setThreadLocalMap(threadLocalMap = new InternalThreadLocalMap());
        }
        return threadLocalMap;
    }

    private static InternalThreadLocalMap slowGet() {
        /**
         * ThreadLocal -> JDK (ThreadLocal)
         * 1、第一次，InternalThreadLocalMap 有可能为 null，
         * 2、需要新建一个 `InternalThreadLocalMap`
         * 3、并设置在 ThreadLocal 中。
         */
        ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = UnpaddedInternalThreadLocalMap.slowThreadLocalMap;
        InternalThreadLocalMap ret = slowThreadLocalMap.get();
        if (ret == null) {
            ret = new InternalThreadLocalMap();
            slowThreadLocalMap.set(ret);
        }
        return ret;
    }

    public static void remove() {
        Thread thread = Thread.currentThread();
        if (thread instanceof FastThreadLocalThread) {
            ((FastThreadLocalThread) thread).setThreadLocalMap(null);
        } else {
            slowThreadLocalMap.remove();
        }
    }

    public static void destroy() {
        slowThreadLocalMap.remove();
    }

    /**
     * 获取下个有效，下标。【 原子自增】
     * @return
     */
    public static int nextVariableIndex() {
        // 进行原子自增。
        int index = nextIndex.getAndIncrement();
        if (index < 0) {
            nextIndex.decrementAndGet();
            throw new IllegalStateException("too many thread-local indexed variables");
        }
        return index;
    }

    public static int lastVariableIndex() {
        return nextIndex.get() - 1;
    }

    // Cache line padding (must be public)
    // With CompressedOops enabled, an instance of this class should occupy at least 128 bytes.
    public long rp1, rp2, rp3, rp4, rp5, rp6, rp7, rp8, rp9;

    private InternalThreadLocalMap() {
        /**
         *  创建一个长度为32的 对象数组， {@link #newIndexedVariableTable()}
         *     1、对象状态 `UNSET`,表示下标的值没有被设置。
         */
        super(newIndexedVariableTable());
    }

    /**
     * 初始化创建 32 对象数组。
     * @return
     */
    private static Object[] newIndexedVariableTable() {
        Object[] array = new Object[32];
        Arrays.fill(array, UNSET);
        return array;
    }

    public int size() {
        int count = 0;

        if (futureListenerStackDepth != 0) {
            count ++;
        }
        if (localChannelReaderStackDepth != 0) {
            count ++;
        }
        if (handlerSharableCache != null) {
            count ++;
        }
        if (counterHashCode != null) {
            count ++;
        }
        if (random != null) {
            count ++;
        }
        if (typeParameterMatcherGetCache != null) {
            count ++;
        }
        if (typeParameterMatcherFindCache != null) {
            count ++;
        }
        if (stringBuilder != null) {
            count ++;
        }
        if (charsetEncoderCache != null) {
            count ++;
        }
        if (charsetDecoderCache != null) {
            count ++;
        }
        if (arrayList != null) {
            count ++;
        }

        for (Object o: indexedVariables) {
            if (o != UNSET) {
                count ++;
            }
        }

        // We should subtract 1 from the count because the first element in 'indexedVariables' is reserved
        // by 'FastThreadLocal' to keep the list of 'FastThreadLocal's to remove on 'FastThreadLocal.removeAll()'.
        return count - 1;
    }

    public StringBuilder stringBuilder() {
        StringBuilder sb = stringBuilder;
        if (sb == null) {
            return stringBuilder = new StringBuilder(STRING_BUILDER_INITIAL_SIZE);
        }
        if (sb.capacity() > STRING_BUILDER_MAX_SIZE) {
            sb.setLength(STRING_BUILDER_INITIAL_SIZE);
            sb.trimToSize();
        }
        sb.setLength(0);
        return sb;
    }

    public Map<Charset, CharsetEncoder> charsetEncoderCache() {
        Map<Charset, CharsetEncoder> cache = charsetEncoderCache;
        if (cache == null) {
            charsetEncoderCache = cache = new IdentityHashMap<Charset, CharsetEncoder>();
        }
        return cache;
    }

    public Map<Charset, CharsetDecoder> charsetDecoderCache() {
        Map<Charset, CharsetDecoder> cache = charsetDecoderCache;
        if (cache == null) {
            charsetDecoderCache = cache = new IdentityHashMap<Charset, CharsetDecoder>();
        }
        return cache;
    }

    public <E> ArrayList<E> arrayList() {
        return arrayList(DEFAULT_ARRAY_LIST_INITIAL_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public <E> ArrayList<E> arrayList(int minCapacity) {
        ArrayList<E> list = (ArrayList<E>) arrayList;
        if (list == null) {
            arrayList = new ArrayList<Object>(minCapacity);
            return (ArrayList<E>) arrayList;
        }
        list.clear();
        list.ensureCapacity(minCapacity);
        return list;
    }

    public int futureListenerStackDepth() {
        return futureListenerStackDepth;
    }

    public void setFutureListenerStackDepth(int futureListenerStackDepth) {
        this.futureListenerStackDepth = futureListenerStackDepth;
    }

    public ThreadLocalRandom random() {
        ThreadLocalRandom r = random;
        if (r == null) {
            random = r = new ThreadLocalRandom();
        }
        return r;
    }

    public Map<Class<?>, TypeParameterMatcher> typeParameterMatcherGetCache() {
        Map<Class<?>, TypeParameterMatcher> cache = typeParameterMatcherGetCache;
        if (cache == null) {
            typeParameterMatcherGetCache = cache = new IdentityHashMap<Class<?>, TypeParameterMatcher>();
        }
        return cache;
    }

    public Map<Class<?>, Map<String, TypeParameterMatcher>> typeParameterMatcherFindCache() {
        Map<Class<?>, Map<String, TypeParameterMatcher>> cache = typeParameterMatcherFindCache;
        if (cache == null) {
            typeParameterMatcherFindCache = cache = new IdentityHashMap<Class<?>, Map<String, TypeParameterMatcher>>();
        }
        return cache;
    }

    @Deprecated
    public IntegerHolder counterHashCode() {
        return counterHashCode;
    }

    @Deprecated
    public void setCounterHashCode(IntegerHolder counterHashCode) {
        this.counterHashCode = counterHashCode;
    }

    public Map<Class<?>, Boolean> handlerSharableCache() {
        Map<Class<?>, Boolean> cache = handlerSharableCache;
        if (cache == null) {
            // Start with small capacity to keep memory overhead as low as possible.
            handlerSharableCache = cache = new WeakHashMap<Class<?>, Boolean>(4);
        }
        return cache;
    }

    public int localChannelReaderStackDepth() {
        return localChannelReaderStackDepth;
    }

    public void setLocalChannelReaderStackDepth(int localChannelReaderStackDepth) {
        this.localChannelReaderStackDepth = localChannelReaderStackDepth;
    }

    public Object indexedVariable(int index) {
        /**
         * indexedVariables， = InternalThreadLocalMap对象中维护的数组，初始值32.
         */
        Object[] lookup = indexedVariables;
        return index < lookup.length? lookup[index] : UNSET;
    }

    /**
     * @return {@code true} if and only if a new thread-local variable has been created
     */
    public boolean setIndexedVariable(int index, Object value) {
        /**
         * indexedVariables  = InternalThreadLocalMap对象中维护的数组对象，初始值32。
         * 则直接通过下标 `index` 设置新创建的线程共享对象
         */
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object oldValue = lookup[index];

            // 直接将数组 index 位置设置为 value，时间复杂度为 O(1)
            lookup[index] = value;
            return oldValue == UNSET;
        } else {
            /**
             * index > indexedVariables.length
             * 将数组进行扩容。{@link #expandIndexedVariableTableAndSet(int, Object)}
             */
            expandIndexedVariableTableAndSet(index, value);
            return true;
        }
    }

    private void expandIndexedVariableTableAndSet(int index, Object value) {
        Object[] oldArray = indexedVariables;
        final int oldCapacity = oldArray.length;
        int newCapacity = index;
        newCapacity |= newCapacity >>>  1;
        newCapacity |= newCapacity >>>  2;
        newCapacity |= newCapacity >>>  4;
        newCapacity |= newCapacity >>>  8;
        newCapacity |= newCapacity >>> 16;
        newCapacity ++;

        Object[] newArray = Arrays.copyOf(oldArray, newCapacity);
        Arrays.fill(newArray, oldCapacity, newArray.length, UNSET);
        newArray[index] = value;
        indexedVariables = newArray;
    }

    /**
     * 根据 index，将值设置为 UNSET.
     */
    public Object removeIndexedVariable(int index) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object v = lookup[index];
            lookup[index] = UNSET;
            return v;
        } else {
            return UNSET;
        }
    }

    public boolean isIndexedVariableSet(int index) {
        Object[] lookup = indexedVariables;
        return index < lookup.length && lookup[index] != UNSET;
    }

    public boolean isCleanerFlagSet(int index) {
        return cleanerFlags != null && cleanerFlags.get(index);
    }

    public void setCleanerFlag(int index) {
        if (cleanerFlags == null) {
            cleanerFlags = new BitSet();
        }
        cleanerFlags.set(index);
    }
}
