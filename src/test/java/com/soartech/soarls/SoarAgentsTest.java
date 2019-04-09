package com.soartech.soarls;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.apache.commons.io.IOUtils;
import org.junit.Test;

import com.google.gson.Gson;

public class SoarAgentsTest extends LanguageServerTestFixture {

	public SoarAgentsTest() throws Exception {
		super("");
	}

	@Test
	public void readJson() throws IOException {
		
		String soarAgentsJson = IOUtils.toString(this.getClass().getResource("/soarAgents.json"), "UTF-8");
		
		EntryPoints soarAgents = new Gson().fromJson(soarAgentsJson, EntryPoints.class);
		
		// just spot checking parts of the structure
		assertEquals(8, soarAgents.entryPoints.size());
		assertEquals("project", soarAgents.active);
		assertEquals("completion", soarAgents.entryPoints.get(0).name);
		assertEquals("completion/test.soar", soarAgents.entryPoints.get(0).path);
	}
}
