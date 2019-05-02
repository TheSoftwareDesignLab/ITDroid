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
		return (int) (orderCriteria.get(o2.getID()) - orderCriteria.get(o1.getID()));
	}

}
