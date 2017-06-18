package ecomod.common.pollution.thread;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import ecomod.api.pollution.IPollutionEmitter;
import ecomod.api.pollution.IPollutionMultiplier;
import ecomod.api.pollution.PollutionData;
import ecomod.api.pollution.PollutionData.PollutionType;
import ecomod.common.pollution.PollutionManager;
import ecomod.common.pollution.PollutionManager.ChunkPollution;
import ecomod.common.pollution.PollutionUtils;
import ecomod.common.pollution.TEPollutionConfig.TEPollution;
import ecomod.common.utils.EMUtils;
import ecomod.core.EcologyMod;
import ecomod.core.stuff.EMConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

public class WorldProcessingThread extends Thread
{
	private PollutionManager manager;
	private boolean isWorking = false;
	
	private List<Pair<Integer, Integer>> loadedChunks = Collections.synchronizedList(new ArrayList<Pair<Integer,Integer>>());
	
	private List<ChunkPollution> scheduledEmissions = Collections.synchronizedList(new ArrayList<ChunkPollution>());
	
	
	public WorldProcessingThread(PollutionManager pm)
	{
		super();
		
		manager = pm;
		
		this.setName("WPT_"+pm.getDim());
		this.setDaemon(true);
		this.setPriority(2);//2 of 10 
	}
	
	@Override
	public void run()
	{
		EcologyMod.log.info("Starting: "+getName());
		
		if(!EMConfig.wptimm)
			slp();
		
		//Some debug stuff
		//EcologyMod.log.info(isInterrupted());
		//EcologyMod.log.info(PollutionUtils.genPMid(manager));
		//EcologyMod.log.info(EcologyMod.ph.threads.containsKey(PollutionUtils.genPMid(manager)));
		
		while(!isInterrupted() && manager != null && EcologyMod.ph.threads.containsKey(PollutionUtils.genPMid(manager)))
		{
			while(Minecraft.getMinecraft().isGamePaused())
				slp(15); //Don't make anything while MC is paused
			
			isWorking = true;
			EcologyMod.log.info("Starting world processing... (dim "+manager.getDim()+")");
			
			World world = manager.getWorld();
			
			List<Chunk> chks = new ArrayList<Chunk>();
			
			for(Pair<Integer, Integer> c : getLoadedChunks())
				chks.add(PollutionUtils.coordsToChunk(world, c));
			
			for(Chunk c : chks)
			{
				PollutionData d = calculateChunkPollution(c).add(manager.getChunkPollution(Pair.of(c.xPosition, c.zPosition)).getPollution());
				Map<PollutionType, Float> m = calculateMultipliers(c);
				
				for(ChunkPollution cp : getScheduledEmissions())
					if(cp.getX() == c.xPosition && cp.getZ() == c.zPosition)
					{
						d.add(cp.getPollution());
					}
				
				for(PollutionType pt : PollutionType.values())
				{
					d = d.multiply(pt, m.get(pt));
				}
				
				manager.setChunkPollution(new ChunkPollution(c.xPosition, c.zPosition, d));
			}
			
			manager.save();
			slp();
		}
		
		isWorking = false;
		manager.save();
	}
	
	public PollutionManager getPM()
	{
		return manager;
	}
	
	public List<ChunkPollution> getScheduledEmissions()
	{
		return scheduledEmissions;
	}
	
	public boolean isWorking()
	{
		return isWorking;
	}
	
	public List<Pair<Integer,Integer>> getLoadedChunks()
	{
		return loadedChunks;
	}
	
	private void slp(int seconds)
	{
		isWorking = false;
		
		try
		{
			EcologyMod.log.info("Sleeping for "+seconds+" seconds");
			sleep(seconds*1000);
		} 
		catch (InterruptedException e)
		{
			e.printStackTrace();
			return;
		}
	}
	
	private void slp()
	{
		slp(EMConfig.wptcd);
	}
	
	public PollutionData calculateChunkPollution(Chunk c)
	{
		List<TileEntity> tes = new LinkedList<TileEntity>(c.getTileEntityMap().values());
		
		PollutionData ret = new PollutionData();
		
		for(TileEntity te : tes)
		{
			if(te instanceof IPollutionEmitter)
			{
				IPollutionEmitter ipe = (IPollutionEmitter) te;
				ret.add(ipe.pollutionEmission());
			}
			else
			{
				if(EcologyMod.instance.tepc.hasTile(te))
				{
					if(PollutionUtils.isTEWorking(te))
					{
						TEPollution tep = EcologyMod.instance.tepc.getTEP(te);
						if(tep != null)
							ret.add(tep.getEmission().multiply(PollutionType.WATER, EMUtils.countWaterInRadius(c.getWorld(), te.getPos(), EMConfig.wpr)));
					}
				}
			}
		}
		
		return ret.multiplyAll(EMConfig.wptcd/60);
	}
	
	public Map<PollutionType, Float> calculateMultipliers(Chunk c)
	{
		List<TileEntity> tes = new LinkedList<TileEntity>(c.getTileEntityMap().values());
		
		Map<PollutionType, Float> ret = new HashMap<PollutionType, Float>();
		
		//Multipliers
		float mA = 1, mW = 1, mS = 1, mN = 1;
		
		for(TileEntity te : tes)
			if(te instanceof IPollutionMultiplier)
			{
				IPollutionMultiplier ipm = (IPollutionMultiplier) te;
				mA *= ipm.pollutionFactor(PollutionType.AIR);
				mW *= ipm.pollutionFactor(PollutionType.WATER);
				mS *= ipm.pollutionFactor(PollutionType.SOIL);
			}
				
		ret.put(PollutionType.AIR, mA);
		ret.put(PollutionType.WATER, mW);
		ret.put(PollutionType.SOIL, mS);
		
		return ret;
	}
}