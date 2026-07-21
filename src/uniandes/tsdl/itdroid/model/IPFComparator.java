package uniandes.tsdl.itdroid.model;

import java.util.Comparator;
import java.util.Map;

public class IPFComparator implements Comparator<IPF>{

	Map<String, Long> orderCriteria;
	
	public IPFComparator(Map<String, Long> result) {
		super();
		this.orderCriteria = result;
	}

	@Override
	public int compare(IPF o1, IPF o2) {
		// Sort by descending occurrence count. getOrDefault avoids NPEs when an ID is
		// missing from the map, and Long.compare avoids the overflow of casting a long
		// difference to int (which would break the comparator contract).
		long c1 = orderCriteria.getOrDefault(o1.getID(), 0L);
		long c2 = orderCriteria.getOrDefault(o2.getID(), 0L);
		return Long.compare(c2, c1);
	}

}
