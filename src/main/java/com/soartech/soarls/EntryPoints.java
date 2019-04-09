package com.soartech.soarls;

import java.util.List;

public class EntryPoints {

	public List<EntryPoint> entryPoints;
	public String active;

	public EntryPoints() {}
	
	public static class EntryPoint {
		public String path;
		public String name;
		
		public EntryPoint() {}
	}
	
}
