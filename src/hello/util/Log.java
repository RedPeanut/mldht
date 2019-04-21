package hello.util;

public class Log {
	public static void d(String tag, String msg) {
		System.out.println(String.format("[%s] %s", tag, msg));
	}
}
