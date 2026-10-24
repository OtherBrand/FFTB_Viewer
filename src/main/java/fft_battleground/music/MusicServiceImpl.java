package fft_battleground.music;

import java.io.IOException;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import fft_battleground.dump.DumpDataProvider;
import fft_battleground.dump.cache.set.MusicCache;
import fft_battleground.event.detector.model.MusicEvent;
import fft_battleground.exception.DumpException;
import fft_battleground.music.model.Music;
import fft_battleground.repo.model.MusicListenCount;
import fft_battleground.repo.repository.MusicListenCountRepo;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MusicServiceImpl implements MusicService {
	
	@Autowired
	private MusicListenCountRepo musicListenCountRepo;
	
	@Autowired
	private DumpDataProvider dumpDataProvider;
	
	@Autowired
	private MusicOccurencesCache occurencesCache;
	
	@Autowired
	private MusicCache musicCache;
	
	private final ReadWriteLock musicReadWriteLock = new ReentrantReadWriteLock();
	private final BlockingQueue<MusicEvent> newSongOccurenceQueue = new LinkedBlockingQueue<>();
	
	private MusicEvent currentMusicEvent;
	
	@Override
	public Collection<Music> getPlaylist() {
		Collection<Music> musicList;
		Lock musicReadLock = musicReadWriteLock.readLock();
		try {
			musicReadLock.lock();
			musicList = this.musicCache.getSet();
		} finally {
			musicReadLock.unlock();
		}
		
		return musicList;
	}

	@Override
	public void addOccurence(MusicEvent event) {
		Lock readLock = this.musicReadWriteLock.readLock();
		try {
			readLock.lock();
			if(this.currentMusicEvent == null || !this.currentMusicEvent.equals(event)) {
				this.currentMusicEvent = event;
				this.newSongOccurenceQueue.add(event);
				log.info("New Song Found!  Song is \"{}.\"", event.getSongName());
			} else {
				log.info("Duplicate song \"{}\"", event.getSongName());
			}
		} catch(Exception e) { 
			log.error("Error calling addOccurence", e);
		}  finally {
			readLock.unlock();
		}
		
	}

	@Override
	public void updatePlaylist() throws DumpException {
		log.info("Updating music data");

		Set<Music> dumpMusicData = this.loadMusicDataFromDump();
		Collection<Music> musicList = dumpMusicData.stream().collect(Collectors.toList()).stream().sorted().collect(Collectors.toList());
		List<MusicListenCount> countsToAdd = new ArrayList<>();
		Timestamp now = Timestamp.from(Instant.now());
		for(Music music: musicList) {
			MusicListenCount occurences = this.occurencesCache.getOccurencesbySong(music.getSongName());
			if(occurences != null) {
				music.setOccurences(occurences.getOccurences());
				music.setMostRecentOccurence(occurences.getUpdateDateTime());
				music.setDateAdded(occurences.getCreateDateTime());
			} else {
				music.setOccurences(0L);
				music.setMostRecentOccurence(now);
				music.setDateAdded(now);
				MusicListenCount newOccurence = new MusicListenCount(music.getSongName());
				countsToAdd.add(newOccurence);
			}
		}
		Lock writeLock = this.musicReadWriteLock.writeLock();
		try {
			writeLock.lock();
			this.musicCache.reload(musicList);
			this.occurencesCache.addNewOccurence(countsToAdd);
			for(MusicListenCount mlc : countsToAdd) {
				this.updateMusicListenCountRepo(mlc);
			}
		} catch(Exception e) { 
			log.error("Error calling updatePlaylist", e);
		} finally {
			writeLock.unlock();
		}
		log.info("music data update complete");
	}

	@Override
	public void updateOccurences() {
		Lock writeLock = this.musicReadWriteLock.writeLock();
		try {
			writeLock.lock();
			if(this.newSongOccurenceQueue.isEmpty()) {
				log.warn("No songs found to add to music listen count!");
			}
			List<MusicListenCount> countsToAdd = new ArrayList<>();
			while(!this.newSongOccurenceQueue.isEmpty()) {
				MusicEvent event = this.newSongOccurenceQueue.poll();
				MusicListenCount mlc= this.occurencesCache.getOccurencesByClosestSong(event.getSongName());
				if(mlc == null) {
					mlc = new MusicListenCount(event.getSongName(), 1);
					mlc.setCreateDateTime(new Timestamp(event.getEventTime().getTime()));
					mlc.setUpdateDateTime(new Timestamp(event.getEventTime().getTime()));
					countsToAdd.add(mlc);
				} else {
					mlc.setUpdateDateTime(new Timestamp(event.getEventTime().getTime()));
					mlc.setOccurences(mlc.getOccurences() + 1);
				}
				this.updateMusicListenCountRepo(mlc);
			}
			this.occurencesCache.addNewOccurence(countsToAdd);
			log.info("The music cache currently has {} entries", this.musicCache.size());
		} catch(Exception e) { 
			log.error("Error calling updateOccurences", e);
		} finally {
			writeLock.unlock();
		}
	}
	
	@Override
	public void freshLoad(Collection<Music> dumpMusicData, List<MusicListenCount> occurrenceData) {
		Lock writeLock = this.musicReadWriteLock.writeLock();
		try {
			writeLock.lock();
			this.occurencesCache.addNewOccurence(occurrenceData);
			List<MusicListenCount> countsToAdd = new ArrayList<>();
			Timestamp now = Timestamp.from(Instant.now());
			for(Music music: dumpMusicData) {
				MusicListenCount occurences = this.occurencesCache.getOccurencesbySong(music.getSongName());
				if(occurences != null) {
					music.setOccurences(occurences.getOccurences());
					music.setMostRecentOccurence(occurences.getUpdateDateTime());
					music.setDateAdded(occurences.getCreateDateTime());
				} else {
					music.setOccurences(0L);
					music.setMostRecentOccurence(now);
					music.setDateAdded(now);
					MusicListenCount newOccurence = new MusicListenCount(music.getSongName());
					countsToAdd.add(newOccurence);
					this.updateMusicListenCountRepo(newOccurence);
				}
			}
			this.occurencesCache.addNewOccurence(countsToAdd);
			this.musicCache.reload(dumpMusicData);
			log.info("The music cache currently has {} entries", this.musicCache.size());
		} catch(Exception e) { 
			log.error("Error calling freshLoad", e);
		}  finally {
			writeLock.unlock();
		}
	}
	
	@Override
	public Set<Music> loadMusicDataFromDump() throws DumpException {
		//playlist.xml is ~9MB over plain HTTP and occasionally truncates mid-transfer; a cut stream
		//reads as a clean EOF so the parser just sees a fragment and throws, so retry fetch+parse
		DumpException lastFailure = null;
		for(int attempt = 1; attempt <= 3; attempt++) {
			try {
				Set<Music> musicSet = this.parseMusicXml(this.dumpDataProvider.getMusicXmlString());
				if(!musicSet.isEmpty()) {
					return musicSet;
				}
				log.warn("playlist.xml returned no songs (attempt {})", attempt);
			} catch (IOException|SAXException|ParserConfigurationException e) {
				log.error("error updating music data from cache (attempt {})", attempt, e);
				lastFailure = new DumpException(e, "error updating music data from cache (attempt " + attempt + ")");
			}
			try {
				Thread.sleep(2000L * attempt);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw lastFailure != null ? lastFailure : new DumpException("playlist.xml returned no usable data after 3 attempts");
	}

	private Set<Music> parseMusicXml(String xmlData) throws IOException, SAXException, ParserConfigurationException {
		Set<Music> musicSet = new HashSet<>();
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder;
		dBuilder = dbFactory.newDocumentBuilder();
		Document doc = dBuilder.parse(new InputSource(new StringReader(xmlData)));
		doc.getDocumentElement().normalize();

		NodeList leafs = doc.getElementsByTagName("leaf");
		for(int i = 0; i < leafs.getLength(); i++) {
			Node node = leafs.item(i);
			if(node.getNodeType() == Node.ELEMENT_NODE) {
				Element element = (Element) node;
				String name = element.getAttribute("uri");
				name = StringUtils.substringAfterLast(name, "/");
				name = StringUtils.substringBefore(name, ".mp3");
				name = URLDecoder.decode(name, StandardCharsets.UTF_8.toString());
				musicSet.add(new Music(name, element.getAttribute("id"), element.getAttribute("duration")));
			}
		}

		return musicSet;
	}
	
	@Override
	@Transactional(readOnly = true)
	public List<MusicListenCount> loadMusicListenCountFromRepo() {
		List<MusicListenCount> list = this.musicListenCountRepo.findAll();
		return list;
	}
	
	@Override
	public Date getFirstOccurenceDate() {
		return this.occurencesCache.getFirstOccurence();
	}
	
	@Transactional
	private void updateMusicListenCountRepo(MusicListenCount mlc) {
		this.musicListenCountRepo.saveAndFlush(mlc);
		
		return;
	}

}
