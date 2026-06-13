public class TestUnsafe {
    public static void main(String[] args) throws Exception {
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
        long ptr = unsafe.allocateMemory(16);
        System.out.println("Allocated pointer: " + ptr);
        unsafe.freeMemory(ptr);
    }
}
