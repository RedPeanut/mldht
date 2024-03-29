package the8472.test.bencode;

import static org.junit.Assert.*;
import static the8472.bencode.Utils.buf2str;
import static the8472.bencode.Utils.str2buf;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import the8472.bencode.BEncoder;

public class EncoderTest {

	@Test
	public void test() {
		Map<String, Object> root = new HashMap<>();
		List<Object> l = new ArrayList<>();
		l.add(1234L);
		l.add(str2buf("foo"));
		
		root.put("zz", 3L);
		root.put("xx", 1L);
		root.put("yy", 1L);
		root.put("aa", 1L);
		root.put("bb", 1L);
		root.put("c", l);
		
		BEncoder enc = new BEncoder();
		ByteBuffer out = enc.encode(root, 1024);
		
		assertEquals(str2buf("d2:aai1e2:bbi1e1:cli1234e3:fooe2:xxi1e2:yyi1e2:zzi3ee"), out);
	}

}
