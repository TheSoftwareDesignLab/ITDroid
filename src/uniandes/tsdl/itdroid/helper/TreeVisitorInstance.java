package uniandes.tsdl.itdroid.helper;

import java.util.HashSet;

import org.antlr.runtime.tree.CommonTree;
import org.antlr.runtime.tree.TreeVisitor;
import org.antlr.runtime.tree.TreeVisitorAction;

public class TreeVisitorInstance extends TreeVisitor{
	
	private HashSet<CommonTree> calls;
	String filePath;
	
	public TreeVisitorInstance(String filePath) {
		calls = new HashSet<CommonTree>();
		this.filePath = filePath;
	}
	
	@Override
	public Object visit(Object tt, TreeVisitorAction action) {
		CommonTree t = (CommonTree) tt;
		if(ASTHelper.isValidLocation(t)){
			calls.add(t);
		}
		return super.visit(t, action);
	}
	
	

	public HashSet<CommonTree> getCalls() {
		return calls;
	}
}
