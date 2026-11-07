package fft_battleground.repo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import fft_battleground.repo.model.PlayerChatStats;

@Repository
public interface PlayerChatStatsRepo extends JpaRepository<PlayerChatStats, String> {

}
