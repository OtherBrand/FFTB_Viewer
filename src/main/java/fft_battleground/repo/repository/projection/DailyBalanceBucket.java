package fft_battleground.repo.repository.projection;

public interface DailyBalanceBucket {

	public String getBucket();

	public Integer getStartBalance();

	public Integer getEndBalance();

	public Integer getMinBalance();

	public Integer getMaxBalance();

	public Long getNetChange();

	public Long getUpdates();
}
