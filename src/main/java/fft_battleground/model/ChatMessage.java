package fft_battleground.model;

import java.util.Date;

import lombok.Data;

@Data
public class ChatMessage {

    private String channel;
    private String username;
    private String message;
    private Boolean isSubscriber = false;

    private Date messageTime;

    /** Twitch-native emote codes present in this message (lowercased); empty when the tag is absent */
    private java.util.Set<String> emoteCodes = java.util.Set.of();
    
    public ChatMessage() {
    	this.messageTime = new Date();
    }

    public ChatMessage(String message) {
    	this.message = message;
    	this.isSubscriber = false;
    	
    	this.messageTime = new Date();
    }
    
    public ChatMessage(String username, String message) {
        this.username = username;
        this.message = message;
        this.isSubscriber = false;
        
    	this.messageTime = new Date();
    }
    
    public ChatMessage(String username, String message, boolean isSubscriber) {
    	this.username = username;
    	this.message = message;
    	this.isSubscriber = isSubscriber;

    	this.messageTime = new Date();
    }

	public ChatMessage(String channel, String userName, String content, boolean sub) {
		this.username = userName;
		this.message = content;
		this.channel = channel;
		this.isSubscriber = sub;

		this.messageTime = new Date();
	}

	/** with Twitch-native emote codes (from the IRC emotes tag) — word-cloud ingestion drops them */
	public ChatMessage(String channel, String userName, String content, boolean sub, java.util.Set<String> emoteCodes) {
		this.username = userName;
		this.message = content;
		this.channel = channel;
		this.isSubscriber = sub;
		this.emoteCodes = emoteCodes != null ? emoteCodes : java.util.Set.of();

		this.messageTime = new Date();
	}

	public java.util.Set<String> getEmoteCodes() {
		return this.emoteCodes;
	}

}