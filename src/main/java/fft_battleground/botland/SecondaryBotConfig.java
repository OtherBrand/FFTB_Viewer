package fft_battleground.botland;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import fft_battleground.botland.model.BotData;
import fft_battleground.botland.model.BotParam;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class SecondaryBotConfig {
	private Resource configFileResouce;
	
	public SecondaryBotConfig(String path) {
		this.configFileResouce = (Resource) new ClassPathResource(path);
	}
	
	@SneakyThrows
	public List<BotData> parseXmlFile() {
		List<BotData> botData = new ArrayList<>();
		Resource resource = (Resource) new ClassPathResource("Botland.xml");
		String line;
		StringBuilder xmlData = new StringBuilder("");
		try(BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
			while((line = reader.readLine()) != null) {
				xmlData.append(line);
			}
		}
		
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		Document doc = dBuilder.parse(new InputSource(new StringReader(xmlData.toString())));
		doc.getDocumentElement().normalize();
		
		NodeList leafs = doc.getElementsByTagName("bot");
		for(int i = 0; i < leafs.getLength(); i++) {
			Node node = leafs.item(i);
			if(node.getNodeType() == Node.ELEMENT_NODE) {
				Element element = (Element) node;
				String name = StringUtils.trim(element.getAttribute("id"));
				String classname = StringUtils.trim(element.getAttribute("classname"));
				String canPrimaryString = StringUtils.trim(element.getAttribute("canPrimary"));
				String botDescription = StringUtils.trim(element.getAttribute("description"));
				Boolean canPrimary = Boolean.valueOf(canPrimaryString);
				NodeList params = node.getChildNodes();
				Map<String, BotParam> paramMap = new HashMap<>();
				for(int j = 0; j < params.getLength(); j++) {
					Node paramNode = params.item(j);
					if(paramNode.getNodeType() == Node.ELEMENT_NODE) {
						Element paramElement = (Element) paramNode;
						String paramName = StringUtils.trim(paramElement.getAttribute("id"));
						String paramDescription = StringUtils.trim(paramElement.getAttribute("description"));
						String parameter = StringUtils.trim(paramElement.getTextContent());
						paramMap.put(paramName, new BotParam(paramName, parameter, paramDescription));
					}
				}
				BotData newBotData = new BotData(name, classname, paramMap, canPrimary, botDescription);
				String visibleString = StringUtils.trim(element.getAttribute("visible"));
				newBotData.setVisible(StringUtils.isEmpty(visibleString) ? true : Boolean.valueOf(visibleString));
				botData.add(newBotData);
			}
			
		}
		
		return botData;
	}
	
}