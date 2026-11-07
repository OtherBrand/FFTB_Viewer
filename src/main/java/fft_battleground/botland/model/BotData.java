package fft_battleground.botland.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
public class BotData {
	private String name;
	private String classname;
	private boolean canPrimary;
	private String description;

	/** visible="false" roster entries are hidden from /botland and the leaderboard (ghost bots, e.g. the advisor) */
	@JsonIgnore
	private boolean visible = true;
	
	@JsonIgnore
	private Map<String, BotParam> params;
	
	public BotData() {}
	
	public BotData(String name, String classname, Map<String, BotParam> params, String description) {
		this.name = name;
		this.classname = classname;
		this.params = params;
		this.description = description;
		
		this.canPrimary = true;
	}
	
	public BotData(String name, String classname, Map<String, BotParam> params, boolean qualifierForPrimary, String description) {
		this.name = name;
		this.classname = classname;
		this.params = params;
		this.description = description;
		this.canPrimary = true;
	}
	
	@JsonProperty("params")
	public List<BotParamData> getParamList() {
		List<BotParamData> paramList = BotParamData.convertToParamList(this.params);
		return paramList;
	}
}

@Data
@AllArgsConstructor
class BotParamData {
	private String paramName;
	private BotParam param;
	
	public static List<BotParamData> convertToParamList(Map<String, BotParam> params) {
		List<BotParamData> botParamData = params.keySet().stream().map(paramName -> new BotParamData(paramName, params.get(paramName))).collect(Collectors.toList());
		return botParamData;
	}
}