package hello.util;

public class SingleCounter9 {
	
	private static SingleCounter9 instance;
	private int count = 1;
	
	public static SingleCounter9 getInstance() {
		if (instance == null)
			instance = new SingleCounter9();
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
