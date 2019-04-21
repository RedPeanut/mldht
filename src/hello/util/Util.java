package hello.util;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Util {

	/*public static void printStackTrace() {
		StackTraceElement[] trace = new Throwable().getStackTrace();
		for (StackTraceElement traceElement: trace)
			System.out.println("\tat " + traceElement);
	}*/

	/*private static char[] hexDigits = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
	public static String toHexString(byte[] bytes) {
		String s = "";
		for (int i=0;i<bytes.length;i++) {
			s += hexDigits[bytes[i]/16];
			s += hexDigits[bytes[i]%16];
		}
		return s;
	}*/
	
	private static char[] hexArray = "0123456789ABCDEF".toCharArray();
	public static String toHexString(byte[] bytes) {
		char[] chars = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			int v = bytes[i] & 0xFF;
			chars[i*2] = hexArray[v >>> 4];
			chars[i*2+1] = hexArray[v & 0x0F];
		}
		return new String(chars);
	}

	public static String INDENT = "    ";
	
	public static <K, V> void printMap(String key, Map<K, V> map, String indent) {
		if (map != null) {
			
			System.out.println(indent + (key.isEmpty() ? "" : key + " = ") + "{");
			
			Set<K> keys = map.keySet();
			for (Iterator<K> itor = keys.iterator(); itor.hasNext();) {
				String _key = itor.next().toString();
				V value = map.get(_key);
				if (value instanceof Map) {
					printMap(_key, (Map) value, indent + INDENT);
				} else if (value instanceof List) {
					printList(_key, (List) value, indent + INDENT);
				} else if (value instanceof Object[]) {
					//printArray();
					System.out.print(indent + INDENT + _key + " = [");
					Object[] values = (Object[]) value;
					for (int i = 0; i < values.length; i++) {
						System.out.print(values[i]);
						if (i != values.length - 1)
							System.out.print(",");
					}
					System.out.print("]");
					boolean addComma = itor.hasNext();
					System.out.println(addComma ? "," : "");
				} else {
					boolean addComma = itor.hasNext();
					System.out.println(indent + INDENT + _key + " = " + value + (addComma ? "," : ""));
				}
			}
			
			System.out.println(indent + "}");
		}
	}
	
	public static <T> void printList(String key, List<T> list, String indent) {
		if (list != null) {
			
			System.out.println(indent + (key.isEmpty() ? "" : key + " = ") + "[");
			
			for (int i = 0; i < list.size(); i++) {
				T value = list.get(i);
				if (value instanceof Map)
					printMap("", (Map) value, indent + INDENT);
				else {
					boolean addComma = (i != list.size() - 1);
					System.out.println(String.format(indent + INDENT + "[%s] %s" + (addComma ? "," : ""), i, list.get(i)));
				}
			}
			
			System.out.println(indent + "]");
		}
	}

}
