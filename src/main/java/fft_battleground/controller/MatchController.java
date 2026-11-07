package fft_battleground.controller;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.google.common.base.Optional;

import fft_battleground.event.model.BattleGroundEvent;
import fft_battleground.event.model.BattleGroundEventType;
import fft_battleground.metrics.AccessTracker;
import fft_battleground.util.GenericElementOrdering;
import fft_battleground.util.GenericResponse;

import lombok.SneakyThrows;
import springfox.documentation.annotations.ApiIgnore;

@Controller
@RequestMapping("/api/matches")
@ApiIgnore
public class MatchController {

	@Autowired
	private WebsocketThread websocketThread;

	@Autowired
	private AccessTracker accessTracker;

	@RequestMapping(value= "/health", method = RequestMethod.GET)
	public ResponseEntity<GenericResponse<Boolean>> healthCheck(@RequestHeader(value = "User-Agent") String userAgent, Model model, HttpServletRequest request) {
		//this.logAccess("health check", userAgent, request);
		Boolean result = true;
		return GenericResponse.createGenericResponseEntity(result);
	}

	//retired 2026-10-25: the advisor-v2 oracle serves POST /api/matches/forecast
	@ApiIgnore
	@RequestMapping(value = "/advisor", method = RequestMethod.GET)
	public @ResponseBody ResponseEntity<GenericResponse<String>> retiredAdvisor() {
		GenericResponse<String> body = new GenericResponse<>("retired: use POST /api/matches/forecast (advisor v2)", null);
		return ResponseEntity.status(HttpStatus.GONE).body(body);
	}


	@ApiIgnore
	@RequestMapping(value = "/currentData", method = RequestMethod.GET)
	public @ResponseBody ResponseEntity<GenericResponse<List<GenericElementOrdering<BattleGroundEvent>>>> 
	getCurrentMatch(@RequestHeader(value = "User-Agent") String userAgent, Model model, HttpServletRequest request) {
		List<GenericElementOrdering<BattleGroundEvent>> events = this.websocketThread.getCurrentEventCache();
		return GenericResponse.createGenericResponseEntity(events);
	}
	
	@ApiIgnore
	@RequestMapping(value="/currentMenuData", method = RequestMethod.GET)
	public @ResponseBody ResponseEntity<GenericResponse<List<GenericElementOrdering<BattleGroundEvent>>>> 
	getCurrentMenuData(@RequestHeader(value = "User-Agent") String userAgent, Model model, HttpServletRequest request) {
		EnumSet<BattleGroundEventType> menuEvents = EnumSet.of(BattleGroundEventType.BETTING_BEGINS, BattleGroundEventType.BETTING_ENDS,
				BattleGroundEventType.FIGHT_BEGINS);
		List<GenericElementOrdering<BattleGroundEvent>> events = Optional.fromNullable(this.websocketThread.getCurrentEventCache()).or(List.of())
				.stream()
				.filter(geo -> menuEvents.contains(geo.getElement().getEventType()))
				.collect(Collectors.toList());
		return GenericResponse.createGenericResponseEntity(events);
	}

	@ApiIgnore
	@RequestMapping(value="/RetrieveEventById", method=RequestMethod.GET)
	public @ResponseBody ResponseEntity<GenericResponse<List<GenericElementOrdering<BattleGroundEvent>>>> 
		getEventById(@RequestParam(name="ids", required=true) String ids) {
		String[] idsSplitStrings = StringUtils.split(ids, ","); 
		List<Long> idLongList = Arrays.asList(idsSplitStrings).stream()
				.map(id ->Long.valueOf(id)).sorted().collect(Collectors.toList()); 
		List<GenericElementOrdering<BattleGroundEvent>> elements = idLongList.stream().map(id -> this.websocketThread.getEventByOrderId(id))
				.sorted().collect(Collectors.toList());
		
		return GenericResponse.createGenericResponseEntity(elements);
	
	}
	
	@SneakyThrows
	protected void logAccess(String pageName, String userAgent, HttpServletRequest request) {
		this.accessTracker.addAccessEntry(pageName, userAgent, request);
	}
	 
}
