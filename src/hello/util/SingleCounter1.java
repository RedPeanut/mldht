package hello.util;

public class SingleCounter1 {
	
	private static SingleCounter1 instance;
	private int count = 1;
	
	public static SingleCounter1 getInstance() {
		if (instance == null)
			instance = new SingleCounter1();
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
