package hello.util;

public class SingleCounter0 {
	
	private static SingleCounter0 instance;
	private int count = 1;
	
	public static SingleCounter0 getInstance() {
		if (instance == null)
			instance = new SingleCounter0();
		return instance;
	}
	
	public int getAndIncreaseCount() {
		return count++;
	}
	
	public int getCount() {
		return count;
	}
	
	public void increaseCount() {
		count++;
	}
	
}
