package fft_battleground.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import javax.websocket.server.PathParam;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import fft_battleground.model.ChatMessage;
import fft_battleground.util.GenericResponse;
import fft_battleground.util.Router;
import lombok.extern.slf4j.Slf4j;
import springfox.documentation.annotations.ApiIgnore;

@Controller
@RequestMapping("/chat")
@Slf4j
@ApiIgnore
public class ChatMessageController {

	@Autowired
	private Router<ChatMessage> messageSenderRouter;
	
	@Value("${fft_battleground.chatApiKey:}")
	private String chatApiKey;

	@ApiIgnore
	@PostMapping("/messages/{key}")
	public @ResponseBody ResponseEntity<GenericResponse<Integer>>
	receiveChatMessages(@RequestBody List<ChatMessage> messages, @PathParam("key") String key) {
		//fail closed: with a real OAuth'd bot on the wire, injection requires the shared key
		if(StringUtils.isEmpty(this.chatApiKey) || StringUtils.isEmpty(key)
				|| !MessageDigest.isEqual(this.chatApiKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
			log.warn("rejected chat message injection: invalid or missing key");
			return GenericResponse.<Integer>createGenericResponseEntity(null, "unauthorized", HttpStatus.UNAUTHORIZED);
		}
		this.messageSenderRouter.sendAllDataToQueues(messages);
		log.info("received messages: {}", messages);
		return GenericResponse.<Integer>createGenericResponseEntity(null, "success", HttpStatus.OK);
		
	}
	
}
