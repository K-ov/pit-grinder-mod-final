package boats.jojo.grindbot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.StringUtils;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

@Mod(
		modid = "keystrokesmod",
		name = "gb",
		version = "1.13",
		acceptedMinecraftVersions = "1.8.9"
)
public class GrindBot
{
	@EventHandler
	public void init(FMLInitializationEvent event) {
		MinecraftForge.EVENT_BUS.register(this);
		ClientCommandHandler.instance.registerCommand(new KeyCommand());
	}
	@EventHandler
	public void serverLoad(FMLServerStartingEvent event) {
		event.registerServerCommand(new KeyCommand());
	}
	
	private static final Logger logger = LogManager.getLogger();

	static Base64.Encoder base64encoder = Base64.getEncoder();
	static Base64.Decoder base64decoder = Base64.getDecoder();
	
	Minecraft mcInstance = Minecraft.getMinecraft();
	
	String apiUrl = "https://pit-grinder-logic-api-jlrw3.ondigitalocean.app/api/grinder";
	//String apiUrl = "http://127.0.0.1:5000/api/grinder"; // testing url

	boolean loggingEnabled = false;
	
	float curFps = 0;
	
	double mouseTargetX = 0;
	double mouseTargetY = 0;
	double mouseTargetZ = 0;
	
	boolean attackedThisTick = false;
	
	String curTargetName = "null";
	String[] nextTargetNames = null;
	
	static String apiKey = "null";
	
	int minimumFps = 0;
	
	int ticksPerApiCall = 200;
	
	float initialFov = 120;
	float fovWhenGrinding = 120;
	
	double curSpawnLevel = 999;
	
	long lastCalledApi = 0;
	long lastReceivedApiResponse = 0;

	int apiLastPing = 0;
	int apiLastTotalProcessingTime = 0;
	
	long lastTickTime = 0;
	
	List<String> chatMsgs = new ArrayList<>();
	String importantChatMsg = "";
	
	double keyChanceForwardDown = 0; // make good
	double keyChanceForwardUp = 0;
	
	double keyChanceSideDown = 0;
	double keyChanceSideUp = 0;
	
	double keyChanceBackwardDown = 0;
	double keyChanceBackwardUp = 0;
	
	double keyChanceJumpDown = 0;
	double keyChanceJumpUp = 0;
	
	double keyChanceCrouchDown = 0;
	double keyChanceCrouchUp = 0;
	
	double keyChanceSprintDown = 0;
	double keyChanceSprintUp = 0;
	
	double keyChanceUseDown = 0;
	double keyChanceUseUp = 0;
	
	double keyAttackChance = 0;

	double mouseSpeed = 0;
	
	boolean autoClickerEnabled = false;
	long lastToggledAutoClicker = 0;

	boolean grinderEnabled = false;
	long lastToggledGrinder = 0;
	
	long preApiProcessingTime = 0;
	
	String apiMessage = "null";

	double mouseVelX;
	double mouseVelY;
	long lastMouseUpdate;

	@SubscribeEvent
	public void onKeyPress(InputEvent.KeyInputEvent event) {
		long curTime = System.currentTimeMillis();
		
		long toggledGrinderTimeDiff = curTime - lastToggledGrinder;
		
		if (toggledGrinderTimeDiff > 500 && org.lwjgl.input.Keyboard.isKeyDown(Keyboard.KEY_J)) {
			grinderEnabled = !grinderEnabled;
			
			if (grinderEnabled) { // newly enabled
				initialFov = mcInstance.gameSettings.fovSetting;
				chatMsgs.clear(); // reset list of chat messages to avoid picking up ones received earlier
			}
			else { // newly disabled
				allKeysUp();
				mcInstance.gameSettings.fovSetting = initialFov;
			}
			
			lastToggledGrinder = curTime;
		}
		
		long toggledAutoClickerTimeDiff = curTime - lastToggledAutoClicker;
		
		if (toggledAutoClickerTimeDiff > 500 && org.lwjgl.input.Keyboard.isKeyDown(Keyboard.KEY_K)) {
			autoClickerEnabled = !autoClickerEnabled;
			
			lastToggledAutoClicker = curTime;
		}
	}
	
	@SubscribeEvent
	public void overlayFunc(RenderGameOverlayEvent.Post event) {
		long curTime = System.currentTimeMillis();
		try {
			if (event.type == ElementType.HEALTH) {
				return;
			}
			if (event.type == ElementType.ARMOR) {
				return;
			}

			interpolateMousePosition();

			int screenWidth = event.resolution.getScaledWidth();
			int screenHeight = event.resolution.getScaledHeight();

			String[][] infoToDraw = {
				{"Username", mcInstance.thePlayer.getName()},
				{"FPS", Integer.toString((int) curFps)},
				{"API time", apiLastTotalProcessingTime + "ms"},
				{"AutoClicker", autoClickerEnabled ? "ENABLED" : "disabled"},
				{"X", Double.toString(Math.round(mcInstance.thePlayer.posX * 10.0) / 10.0)},
				{"Y", Double.toString(Math.round(mcInstance.thePlayer.posY * 10.0) / 10.0)},
				{"Z", Double.toString(Math.round(mcInstance.thePlayer.posZ * 10.0) / 10.0)},
				{"API msg", apiMessage},
			};

			for(int i = 0; i < infoToDraw.length; i++) {
				String[] curInfo = infoToDraw[i];

				drawText(curInfo[0] + ": " + curInfo[1], 4, 4f + i * 10, 0xFFFFFF);
			}

			int drawKeyboardPositionX = screenWidth - 77;
			int drawKeyboardPositionY = screenHeight - 60;

			if(mcInstance.gameSettings.keyBindForward.isKeyDown()) { // W
				drawText("W", drawKeyboardPositionX + 41f, drawKeyboardPositionY + 4f, 0xFFFFFF);
			}

			if(mcInstance.gameSettings.keyBindBack.isKeyDown()) { // S
				drawText("S", drawKeyboardPositionX + 41f, drawKeyboardPositionY + 22f, 0xFFFFFF);
			}

			if(mcInstance.gameSettings.keyBindLeft.isKeyDown()) { // A
				drawText("A", drawKeyboardPositionX + 23f, drawKeyboardPositionY + 22f, 0xFFFFFF);
			}

			if(mcInstance.gameSettings.keyBindRight.isKeyDown()) { // D
				drawText("D", drawKeyboardPositionX + 59f, drawKeyboardPositionY + 22f, 0xFFFFFF);
			}

			if(mcInstance.gameSettings.keyBindSneak.isKeyDown()) { // Shift
				drawText("Sh", drawKeyboardPositionX + 2f, drawKeyboardPositionY + 22f, 0xFFFFFF);
			}

			if(mcInstance.gameSettings.keyBindSprint.isKeyDown()) { // Ctrl
				drawText("Ct", drawKeyboardPositionX + 3f, drawKeyboardPositionY + 40f, 0xFFFFFF);
			}

			if(mcInstance.gameSettings.keyBindJump.isKeyDown()) { // Space
				drawText("Space", drawKeyboardPositionX + 28f, drawKeyboardPositionY + 40f, 0xFFFFFF);
			}

			if(mcInstance.gameSettings.keyBindAttack.isKeyDown() || attackedThisTick) { // Mouse1
				drawText("LM", drawKeyboardPositionX + 2f, drawKeyboardPositionY + 4f, 0xFFFFFF);
			}

			if(mcInstance.gameSettings.keyBindUseItem.isKeyDown()) { // Mouse2
				drawText("RM", drawKeyboardPositionX + 20f, drawKeyboardPositionY + 4f, 0xFFFFFF);
			}
		
			// bot controlling

			// get fps
			
			curFps = Minecraft.getDebugFPS();
						
			// bot tick handler
				
			long tickTimeDiff = curTime - lastTickTime;

			if (
				grinderEnabled && curTime - (lastReceivedApiResponse - apiLastTotalProcessingTime) >= 1000 // 1000ms per api call
				&& curTime - lastCalledApi >= 500 // absolute minimum time to avoid spamming before any responses received
			) {
				lastCalledApi = curTime;
				callBotApi();
			}
			
			if (tickTimeDiff < 1000 / 20) { // 20 ticks per second
				return;
			}
		}
		catch(Exception e){
			e.printStackTrace();
		}

		// doing bot tick

		lastTickTime = curTime;

		attackedThisTick = false;

		if (grinderEnabled) {
			mcInstance.gameSettings.fovSetting = fovWhenGrinding;
			doBotTick();
		}
	}
	
	@SubscribeEvent
	public void onChat(ClientChatReceivedEvent event) {
		String curChatRaw = StringUtils.stripControlCodes(event.message.getUnformattedText());
		
		curChatRaw = new String(curChatRaw.getBytes(), StandardCharsets.UTF_8); // probably unnecessary
		
		// idk what the first thing is for `!curChatRaw.startsWith(":")`
		if (!curChatRaw.startsWith(":") && (curChatRaw.startsWith("MAJOR EVENT!") || curChatRaw.startsWith("BOUNTY CLAIMED!") || curChatRaw.startsWith("NIGHT QUEST!") || curChatRaw.startsWith("QUICK MATHS!") || curChatRaw.startsWith("DONE!") || curChatRaw.startsWith("MINOR EVENT!") || curChatRaw.startsWith("MYSTIC ITEM!") || curChatRaw.startsWith("PIT LEVEL UP!") || curChatRaw.startsWith("A player has"))) {
			importantChatMsg = curChatRaw;
		}

		chatMsgs.add(curChatRaw);
	}
	
	public void doBotTick() {
		try {
			// go afk if fps too low (usually when world is loading)

			if (curFps < minimumFps) {
				goAfk();
				apiMessage = "fps too low";
				return;
			}
			
			// main things
			
			long timeSinceReceivedApiResponse = System.currentTimeMillis() - lastReceivedApiResponse;
			
			if (timeSinceReceivedApiResponse > 3000) {
				allKeysUp();

				if ((timeSinceReceivedApiResponse / 50) % 20 == 0) {
					goAfk();
					String issueStr = "too long since successful api response: " + timeSinceReceivedApiResponse + "ms. last api ping: " + apiLastPing + "ms. last api time: " + apiLastTotalProcessingTime + " ms.";
					apiMessage = issueStr;
					logger.info(issueStr);
				}

				return;
			}

			if (!curTargetName.equals("null")) {
				double[] curTargetPos = getPlayerPos(curTargetName);
				
				if (curTargetPos[1] > mcInstance.thePlayer.posY + 4 && nextTargetNames.length > 0) {
					makeLog("switching to next target " + nextTargetNames[0] + " because Y of " + curTargetPos[1] + " too high");
					
					curTargetName = nextTargetNames[0];
					nextTargetNames = Arrays.copyOfRange(nextTargetNames, 1, nextTargetNames.length);
					
					curTargetPos = getPlayerPos(curTargetName);
				}
				
				mouseTargetX = curTargetPos[0];
				mouseTargetY = curTargetPos[1] + 1;
				mouseTargetZ = curTargetPos[2];
			}
			
			if (mcInstance.currentScreen == null) {
				if (mouseTargetX != 0 || mouseTargetY != 0 || mouseTargetZ != 0) { // dumb null check
					mouseMove();
				}
				doMovementKeys();
			}
			else {
				allKeysUp();
			}
			
			if (mcInstance.thePlayer.posY > curSpawnLevel - 4 && !curTargetName.equals("null") && !farFromMid()) {

				// in spawn but has target (bad)

				curTargetName = "null";
				
				mouseTargetX = 0;
				mouseTargetY = curSpawnLevel - 4;
				mouseTargetZ = 0;
				
				allKeysUp();

				if (!autoClickerEnabled) { // only needs to switch away from sword if an external KA is enabled
					mcInstance.thePlayer.inventory.currentItem = 5;
				}
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}

	public void reloadKey() {
		String[] possibleKeyFileNames = {"key.txt", "key.txt.txt", "key", "token.txt", "token.txt.txt", "token"}; // from best to worst...

		boolean foundKeyFile = false;

		for (String curPossibleKeyFileName : possibleKeyFileNames) {
			File potentialKeyFile = new File(curPossibleKeyFileName);

			if (potentialKeyFile.isFile()) {
				// key file found, read it
				try (FileInputStream inputStream = new FileInputStream(curPossibleKeyFileName)) {
					apiKey = IOUtils.toString(inputStream);

					logger.info("set key");

					foundKeyFile = true;

					break; // only breaks if reading key file didn't error
				} catch (Exception e) {
					logger.info("reading key error");
					apiMessage = "error reading key";
					e.printStackTrace();
				}
			}
		}
		
		if (!foundKeyFile) {
			apiMessage = "no key file found";
		}
	}
	
	public void callBotApi() {
		// set key from file if unset
		if (apiKey.equals("null")) {
			reloadKey();
		}

		// return if key is still null - no key was read so no point calling API
		if (apiKey.equals("null")) {
			return;
		}
		
		makeLog("getting api url: " + apiUrl);
		
		preApiProcessingTime = System.currentTimeMillis();
		
		// construct client info string
		
		StringBuilder infoBuilder = new StringBuilder();
		String dataSeparator = "##!##";
		
		// auth key
		
		infoBuilder.append(apiKey).append(dataSeparator);
		
		// client username
		
		infoBuilder.append(mcInstance.thePlayer.getName()).append(dataSeparator);
		
		// client uuid
		
		infoBuilder.append(EntityPlayer.getUUID(mcInstance.thePlayer.getGameProfile())).append(dataSeparator);
		
		// client position + viewing angles
		String positionAnglesSeparator = ":::";
		String positionAnglesStr =
				mcInstance.thePlayer.posX + positionAnglesSeparator
						+ mcInstance.thePlayer.posY + positionAnglesSeparator
						+ mcInstance.thePlayer.posZ + positionAnglesSeparator
						+ mcInstance.thePlayer.rotationPitch + positionAnglesSeparator
						+ mcInstance.thePlayer.rotationYaw + positionAnglesSeparator;

		infoBuilder.append(positionAnglesStr).append(dataSeparator);

		// client inventory
		StringBuilder invStr = new StringBuilder();
		String invStrSeparator = "!!!";
		
		for(int i = 0; i < mcInstance.thePlayer.inventoryContainer.getInventory().size(); i++){
			ItemStack curItem = mcInstance.thePlayer.inventoryContainer.getInventory().get(i);
			
			String curItemName = "air";
			int curItemStackSize = 0;
			
			if (curItem != null) {
				curItemName = curItem.getItem().getRegistryName().split(":")[1];
				curItemStackSize = curItem.stackSize;
			}
			
			invStr.append(curItemName).append(":::").append(curItemStackSize).append(invStrSeparator);
		}
		
		infoBuilder.append(invStr).append(dataSeparator);
		
		// players
			
		List<EntityPlayer> playerList = mcInstance.theWorld.playerEntities;
		
		playerList = playerList
				  .stream()
				  .filter(player -> !player.isInvisible())
				  .collect(Collectors.toList());

		// format:
		// !!!username:::x:::y:::z:::health:::armor!!!
		
		StringBuilder playersStr = new StringBuilder();
		String playerSeparator = "!!!";
		
		for(int i = 0; i < Math.min(128, playerList.size()); i++){
			String intraPlayerSeparator = ":::";
			
			EntityPlayer curPlayer = playerList.get(i);
			
			String curPlayerUsername = curPlayer.getName();
			
			BlockPos curPlayerPosition = curPlayer.getPosition();
			double curPlayerPositionX = curPlayerPosition.getX();
			double curPlayerPositionY = curPlayerPosition.getY();
			double curPlayerPositionZ = curPlayerPosition.getZ();
			
			float curPlayerHealth = curPlayer.getHealth();
			
			int curPlayerArmor = curPlayer.getTotalArmorValue();

			String curPlayerStr = curPlayerUsername + intraPlayerSeparator +
					curPlayerPositionX + intraPlayerSeparator +
					curPlayerPositionY + intraPlayerSeparator +
					curPlayerPositionZ + intraPlayerSeparator +
					curPlayerHealth + intraPlayerSeparator +
					curPlayerArmor + intraPlayerSeparator;
			
			playersStr.append(curPlayerStr).append(playerSeparator);
		}
		
		infoBuilder.append(playersStr).append(dataSeparator);
		
		// middle block
		
		String middleBlockname = "null";
		try {
			middleBlockname = mcInstance.theWorld.getBlockState(new BlockPos(0, (int) mcInstance.thePlayer.posY - 1, 0)).getBlock().getRegistryName().split(":")[1];
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		
		infoBuilder.append(middleBlockname).append(dataSeparator);
		
		// last chat message

		String chatMsgSeparator = "!#!";
		String chatMsgsStr = "";

		for(int i = 0; i < Math.min(32, chatMsgs.size()); i++) {
			chatMsgsStr = chatMsgs.get(i).replace(dataSeparator, "").replace(chatMsgSeparator, "") + chatMsgSeparator;
		}

		chatMsgs.clear();

		infoBuilder.append(chatMsgsStr).append(dataSeparator);
		
		// container items
		
		String containerStr = "null";
		
		List<ItemStack> containerItems = mcInstance.thePlayer.openContainer.getInventory();
		
		if (containerItems.size() > 46) { // check if a container is open (definitely a better way to do that)
			containerStr = "";
			String containerStrSeparator = "!!!";
			for(int i = 0; i < containerItems.size() - 36; i++){ // minus 36 to cut off inventory
				ItemStack curItem = containerItems.get(i);
				
				String curItemName = "air";
				String curItemDisplayName = "air";
				int curItemStackSize = 0;
				
				if (curItem != null) {
					curItemName = curItem.getItem().getRegistryName().split(":")[1];
					curItemStackSize = curItem.stackSize;
					curItemDisplayName = curItem.getDisplayName();
				}
				
				containerStr = curItemName + ":::" + curItemDisplayName + ":::" + curItemStackSize + containerStrSeparator;
			}
		}
		
		infoBuilder.append(containerStr).append(dataSeparator);
		
		// dropped items
		
		String droppedItemsStr = "";
		String droppedItemsSeparator = "!!!";
		
		List<EntityItem> droppedItems = mcInstance.theWorld.getEntitiesWithinAABB(EntityItem.class, new AxisAlignedBB(new BlockPos(mcInstance.thePlayer.posX - 32, mcInstance.thePlayer.posY - 4, mcInstance.thePlayer.posZ - 32), new BlockPos(mcInstance.thePlayer.posX + 32, mcInstance.thePlayer.posY + 32, mcInstance.thePlayer.posZ + 32)));
		
		for(int i = 0; i < Math.min(128, droppedItems.size()); i++){
			EntityItem curItem = droppedItems.get(i);
			
			String curItemName = curItem.getEntityItem().getItem().getRegistryName().split(":")[1];
			
			double curItemPositionX = curItem.getPosition().getX();
			double curItemPositionY = curItem.getPosition().getY();
			double curItemPositionZ = curItem.getPosition().getZ();
			
			droppedItemsStr = curItemName + ":::" + curItemPositionX + ":::" + curItemPositionY + ":::" + curItemPositionZ + droppedItemsSeparator;
		}
		
		infoBuilder.append(droppedItemsStr).append(dataSeparator);
		
		// important chat msg
		
		if (!importantChatMsg.equals("")) {
			infoBuilder.append(importantChatMsg).append(dataSeparator);
			importantChatMsg = "";
		}
		else {
			infoBuilder.append("null").append(dataSeparator);
		}
		
		// current open gui
		
		String curOpenGui = "null";
		if (mcInstance.currentScreen != null) {
			curOpenGui = mcInstance.currentScreen.getClass().toString();
		}
		
		infoBuilder.append(curOpenGui).append(dataSeparator);
		
		// villager positions
		
		StringBuilder villagersStr = new StringBuilder();
		String villagersSeparator = "!!!";
		
		List<Entity> allEntities = mcInstance.theWorld.getLoadedEntityList();
		
		List<Entity> villagerEntities = allEntities.stream().filter(entity -> entity.getClass().equals(EntityVillager.class)).collect(Collectors.toList());
	
		for(int i = 0; i < Math.min(8, villagerEntities.size()); i++){
			Entity curVillager = villagerEntities.get(i);
			
			double curVillagerPositionX = curVillager.getPosition().getX();
			double curVillagerPositionY = curVillager.getPosition().getY();
			double curVillagerPositionZ = curVillager.getPosition().getZ();
			
			villagersStr.append(curVillagerPositionX).append(":::").append(curVillagerPositionY).append(":::").append(curVillagerPositionZ).append(villagersSeparator);
		}
		
		infoBuilder.append(villagersStr).append(dataSeparator);
		
		// client health
		
		infoBuilder.append(mcInstance.thePlayer.getHealth()).append(dataSeparator);
		
		// client xp level
		
		infoBuilder.append(mcInstance.thePlayer.experienceLevel).append(dataSeparator);

		// mod version

		String modVersion = null;
		ModContainer modContainer = Loader.instance().getIndexedModList().get("keystrokesmod");
		if (modContainer != null) {
			modVersion = modContainer.getVersion();
		}

		infoBuilder.append(modVersion).append(dataSeparator);

		// replace newlines because they mess with the header

		String infoStr = infoBuilder.toString().replace("\n", " ");

		// compress

		infoStr = compressString(infoStr);

		// add "this is compressed" tag to support API version compatibility

		infoStr += "xyzcompressed";
		
		// done, set client info header
		
		String infoStrEnc = new String(infoStr.getBytes(), StandardCharsets.UTF_8);
		
		makeLog("api info header length is " + infoStrEnc.length() + " chars");
		
		// do request
		
		ticksPerApiCall = 20;
		
		long preApiGotTime = System.currentTimeMillis();

		ForkJoinPool.commonPool().execute(() -> {
			HttpGet get = new HttpGet(apiUrl);

			int timeoutMs = 5000;
			RequestConfig requestConfig = RequestConfig.custom()
					.setConnectionRequestTimeout(timeoutMs)
					.setConnectTimeout(timeoutMs)
					.setSocketTimeout(timeoutMs)
					.build();

			get.setConfig(requestConfig);
			get.setHeader("clientinfo", infoStrEnc);

			String apiResponse;
			try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
				HttpResponse response = httpclient.execute(get);
				apiResponse = IOUtils.toString(response.getEntity().getContent());
			} catch (IOException e) {
				e.printStackTrace();
				apiMessage = "api call fatal error";
				return;
			}

			apiLastPing = (int)(System.currentTimeMillis() - preApiGotTime);

			makeLog("api ping was " + apiLastPing + "ms");

			if (apiLastPing > 1000) {
				makeLog("api ping too high");
				apiMessage = "api ping too high - " + apiLastPing + "ms";
				return;
			}

			try {
				ingestApiResponse(apiResponse);
			} catch (Exception exception) {
				exception.printStackTrace();
				apiMessage = "errored on ingesting api response";
			}
		});
	}
	
	public void ingestApiResponse(String apiText) {

		// check if the apiText starts with the compression flag

		if (!apiText.startsWith("xyzcompressed")) {
			String errorStr = "api response - " + apiText;
			makeLog(errorStr);
			apiMessage = errorStr;
			return;
		}

		// remove the compression flag from the apiText before decompression

		apiText = apiText.substring("xyzcompressed".length());

		// now decompress

		apiText = decompressString(apiText);

		// deal with given instructions

		String[] apiStringSplit = apiText.split("##!##");
		
		if (!apiStringSplit[0].equals("null")) {
			nextTargetNames = apiStringSplit[0].split(":::");
			curTargetName = nextTargetNames[0];
			nextTargetNames = Arrays.copyOfRange(nextTargetNames, 1, nextTargetNames.length);
		}
		else {
			curTargetName = "null";
			nextTargetNames = null;
		}
		
		if (!apiStringSplit[1].equals("null")) {
			String chatToSend = apiStringSplit[1];
			if (!chatToSend.contains("/trade")) { // lol
				mcInstance.thePlayer.sendChatMessage(apiStringSplit[1]);
			}
		}
		
		if (!apiStringSplit[2].equals("null")) {
			mcInstance.thePlayer.inventory.currentItem = Integer.parseInt(apiStringSplit[2]);
		}
		
		if (!apiStringSplit[3].equals("null")) {
			String[] keyChancesStringSplit = apiStringSplit[3].split(":::");
			
			if (keyChancesStringSplit.length != 15) {
				makeLog("key chances string split wrong length");
				apiMessage = "api key chances failed";
				return;
			}
			
			keyChanceForwardDown = Double.parseDouble(keyChancesStringSplit[0]);
			keyChanceForwardUp = Double.parseDouble(keyChancesStringSplit[1]);
			
			keyChanceSideDown = Double.parseDouble(keyChancesStringSplit[2]);
			keyChanceSideUp = Double.parseDouble(keyChancesStringSplit[3]);
			
			keyChanceBackwardDown = Double.parseDouble(keyChancesStringSplit[4]);
			keyChanceBackwardUp = Double.parseDouble(keyChancesStringSplit[5]);
			
			keyChanceJumpDown = Double.parseDouble(keyChancesStringSplit[6]);
			keyChanceJumpUp = Double.parseDouble(keyChancesStringSplit[7]);
			
			keyChanceCrouchDown = Double.parseDouble(keyChancesStringSplit[8]);
			keyChanceCrouchUp = Double.parseDouble(keyChancesStringSplit[9]);
			
			keyChanceSprintDown = Double.parseDouble(keyChancesStringSplit[10]);
			keyChanceSprintUp = Double.parseDouble(keyChancesStringSplit[11]);
			
			keyChanceUseDown = Double.parseDouble(keyChancesStringSplit[12]);
			keyChanceUseUp = Double.parseDouble(keyChancesStringSplit[13]);
			
			keyAttackChance = Double.parseDouble(keyChancesStringSplit[14]);
		}
		
		mouseTargetX = 0;
		mouseTargetY = 0;
		mouseTargetZ = 0;
		if (!apiStringSplit[4].equals("null")) {
			String[] mouseTargetStringSplit = apiStringSplit[4].split(":::");
			
			mouseTargetX = Double.parseDouble(mouseTargetStringSplit[0]);
			mouseTargetY = Double.parseDouble(mouseTargetStringSplit[1]);
			mouseTargetZ = Double.parseDouble(mouseTargetStringSplit[2]);
		}
		
		if (!apiStringSplit[5].equals("null")) {
			allKeysUp();
			
			int containerItemToPress = Integer.parseInt(apiStringSplit[5]);
			
			makeLog("pressing container item " + containerItemToPress);
			
			mcInstance.playerController.windowClick(mcInstance.thePlayer.openContainer.windowId, containerItemToPress, 1, 2, mcInstance.thePlayer);
		}
		
		if (!apiStringSplit[6].equals("null")) {
			allKeysUp();
			
			int inventoryItemToDrop = Integer.parseInt(apiStringSplit[6]);
			
			makeLog("dropping inventory item " + inventoryItemToDrop);
			
			mcInstance.playerController.windowClick(mcInstance.thePlayer.openContainer.windowId, inventoryItemToDrop, 1, 4, mcInstance.thePlayer);
		}
		
		if (!apiStringSplit[7].equals("null")) {
			allKeysUp();
			
			int inventoryItemToMove = Integer.parseInt(apiStringSplit[7]);
			
			makeLog("moving inventory item " + inventoryItemToMove);
			
			mcInstance.playerController.windowClick(mcInstance.thePlayer.openContainer.windowId, inventoryItemToMove, 1, 1, mcInstance.thePlayer);
		}
		
		if (!apiStringSplit[8].equals("null")) {
			ticksPerApiCall = Integer.parseInt(apiStringSplit[8]);
		}
		
		if (!apiStringSplit[9].equals("null")) {
			minimumFps = Integer.parseInt(apiStringSplit[9]);
		}
		
		if (!apiStringSplit[10].equals("null")) {
			fovWhenGrinding = Float.parseFloat(apiStringSplit[10]);
		}
		
		if (!apiStringSplit[11].equals("null")) {
			allKeysUp();
			
			if (apiStringSplit[11].equals("true")) {
				mcInstance.currentScreen = null;
			}
		}
		
		if (!apiStringSplit[12].equals("null")) {
			allKeysUp();
			
			if (apiStringSplit[12].equals("true")) {
				pressInventoryKeyIfNoGuiOpen();
			}
		}
		
		if (!apiStringSplit[13].equals("null")) {
			apiMessage = apiStringSplit[13];
		}
		
		if (!apiStringSplit[14].equals("null")) {
			curSpawnLevel = Double.parseDouble(apiStringSplit[14]);
		}

		if (!apiStringSplit[15].equals("null")) {
			mouseSpeed = Double.parseDouble(apiStringSplit[15]);
		}
		
		lastReceivedApiResponse = System.currentTimeMillis();
		apiLastTotalProcessingTime = (int) (System.currentTimeMillis() - preApiProcessingTime);
		
		makeLog("total processing time was " + apiLastTotalProcessingTime + "ms");
	}
	
	public void doMovementKeys() { // so long
		if (Math.random() <= keyChanceForwardUp) {
			setKeyUp(1);
		}
		if (Math.random() <= keyChanceForwardDown) {
			setKeyDown(1);
		}
		
		if (Math.random() <= keyChanceBackwardUp) {
			setKeyUp(2);
		}
		if (Math.random() <= keyChanceBackwardDown) {
			setKeyDown(2);
		}
		
		if (Math.random() <= keyChanceSideUp) {
			setKeyUp(3);
		}
		if (Math.random() <= keyChanceSideDown) {
			setKeyDown(3);
		}
		
		if (Math.random() <= keyChanceSideUp) {
			setKeyUp(4);
		}
		if (Math.random() <= keyChanceSideDown) {
			setKeyDown(4);
		}
		
		if (Math.random() <= keyChanceJumpUp) {
			setKeyUp(5);
		}
		if (Math.random() <= keyChanceJumpDown) {
			setKeyDown(5);
		}
		
		if (Math.random() <= keyChanceCrouchUp) {
			setKeyUp(6);
		}
		if (Math.random() <= keyChanceCrouchDown) {
			setKeyDown(6);
		}
		
		if (Math.random() <= keyChanceSprintUp) {
			setKeyUp(7);
		}
		if (Math.random() <= keyChanceSprintDown) {
			setKeyDown(7);
		}
		
		if (Math.random() <= keyChanceUseUp) {
			setKeyUp(9);
		}
		if (Math.random() <= keyChanceUseDown) {
			setKeyDown(9);
		}
		
		if (Math.random() <= keyAttackChance && (autoClickerEnabled)) {
				doAttack();
		}
	}
	
	public void doAttack() {
		KeyBinding.onTick(mcInstance.gameSettings.keyBindAttack.getKeyCode());
		attackedThisTick = true;
	}

	public void goAfk() {
		mouseVelX = 0;
		mouseVelY = 0;
		allKeysUp();
		pressChatKeyIfNoGuiOpen();
	}

	public void pressInventoryKeyIfNoGuiOpen() {
		if (mcInstance.currentScreen == null) {
			KeyBinding.onTick(mcInstance.gameSettings.keyBindInventory.getKeyCode());
		}
	}

	public void pressChatKeyIfNoGuiOpen() {
		if (mcInstance.currentScreen == null) {
			KeyBinding.onTick(mcInstance.gameSettings.keyBindChat.getKeyCode());
		}
	}
	
	public void allKeysUp() {
		setKeyUp(1);
		setKeyUp(2);
		setKeyUp(3);
		setKeyUp(4);
		setKeyUp(5);
		setKeyUp(6);
		setKeyUp(7);
		setKeyUp(8);
		setKeyUp(9);
	}
	
	public double[] getPlayerPos(String playerName) { // weird
		List<EntityPlayer> playerList = mcInstance.theWorld.playerEntities;
		List<EntityPlayer> playerToGet = playerList.stream().filter(pl -> pl.getName().equals(playerName)).collect(Collectors.toList());
		if(!playerToGet.isEmpty()) {
			Entity foundPlayer = playerToGet.get(0);
			
			return new double[] {foundPlayer.getPosition().getX(), foundPlayer.getPosition().getY(), foundPlayer.getPosition().getZ()};
		}
		else {
			logger.info("could not find player");
			return new double[] {0, 999, 0};
		}
	}

	public void setKeyDown(int whichKey) {
		Minecraft mc = Minecraft.getMinecraft();
		if(whichKey >= 1 && whichKey <= 9) {
			KeyBinding[] keysList = {mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack, mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight, mc.gameSettings.keyBindJump, mc.gameSettings.keyBindSneak, mc.gameSettings.keyBindSprint, mc.gameSettings.keyBindAttack, mc.gameSettings.keyBindUseItem};
			KeyBinding.setKeyBindState(keysList[whichKey-1].getKeyCode(), true);
		}
	}
	public void setKeyUp(int whichKey) {
		Minecraft mc = Minecraft.getMinecraft();
		if(whichKey >= 1 && whichKey <= 9) {
			KeyBinding[] keysList = {mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack, mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight, mc.gameSettings.keyBindJump, mc.gameSettings.keyBindSneak, mc.gameSettings.keyBindSprint, mc.gameSettings.keyBindAttack, mc.gameSettings.keyBindUseItem};
			KeyBinding.setKeyBindState(keysList[whichKey-1].getKeyCode(), false);
		}
	}
	
	public void drawText(String text, float x, float y, int col) {
		mcInstance.fontRendererObj.drawStringWithShadow(text, x, y, col);
	}

	public void interpolateMousePosition() {
		if (!grinderEnabled || mcInstance.currentScreen != null) {
			lastMouseUpdate = 0;
			mouseVelX = 0;
			mouseVelY = 0;
			return;
		}

		long currentTime = System.currentTimeMillis();
		if (lastMouseUpdate != 0) {
			float timePassed = (currentTime - lastMouseUpdate) / 1000f;

			// Limit time passed (so that the change can't be that large)
			timePassed = Math.min(timePassed, 1);

			mcInstance.thePlayer.rotationYaw += mouseVelY * timePassed;
			mcInstance.thePlayer.rotationPitch += mouseVelX * timePassed;

			// Limit pitch
			float pitch = mcInstance.thePlayer.rotationPitch;
			mcInstance.thePlayer.rotationPitch = Math.min(Math.max(-90, pitch), 90);
		}
		lastMouseUpdate = currentTime;
	}

	public void mouseMove() {
		interpolateMousePosition();

		float currentYaw = mcInstance.thePlayer.rotationYaw;
		float currentPitch = mcInstance.thePlayer.rotationPitch;
		double x = mcInstance.thePlayer.posX;
		double y = mcInstance.thePlayer.posY;
		double z = mcInstance.thePlayer.posZ;

		double headHeight = 1.62;

		// old af math probably stupid
		double targetRotY = fixRotY(360 - Math.toDegrees(Math.atan2(mouseTargetX - x, mouseTargetZ - z)));
		double targetRotX = -Math.toDegrees(Math.atan(
				(mouseTargetY - y - headHeight) / Math.hypot(mouseTargetX - x, mouseTargetZ - z)
		));
		
		// add random waviness to target
		targetRotY += timeSinWave(310) * 2 + timeSinWave(500) * 2 + timeSinWave(260) * 2;
		targetRotX += timeSinWave(290) * 2 + timeSinWave(490) * 2 + timeSinWave(270) * 2;
		
		targetRotY = fixRotY(targetRotY);
		targetRotX = fixRotX(targetRotX);
		
		// calculate mouse speed
		double timeNoise = timeSinWave(40)  * 2 +
				timeSinWave(50)  * 2 +
				timeSinWave(100) * 2 +
				timeSinWave(150) * 4 +
				timeSinWave(200) * 6;

		double mouseCurSpeed = mouseSpeed + timeNoise;

		currentYaw = (float) fixRotY(currentYaw);
		
		double diffRotX = targetRotX - currentPitch;
		double diffRotY = range180(targetRotY - currentYaw);
		
		double rotAng = Math.atan2(diffRotY, diffRotX) + Math.PI;
		
		double changeRotX = -Math.cos(rotAng) * mouseCurSpeed / 4;
		double changeRotY = -Math.sin(rotAng) * mouseCurSpeed;

		mouseVelX = Math.abs(diffRotX) < Math.abs(changeRotX)
				? targetRotX - currentPitch
				: changeRotX;
		mouseVelY = Math.abs(diffRotY) < Math.abs(changeRotY)
				? targetRotY - currentYaw
				: changeRotY;

		// Reach target in 1/20th of a second (1 tick)
		double tps = 20.0;
		mouseVelX *= tps;
		mouseVelY *= tps;
	}
	
	public boolean farFromMid() {
		return mcInstance.thePlayer.posX > 32 || mcInstance.thePlayer.posZ > 32;
	}

	public double timeSinWave(double div) { // little odd
		double num = System.currentTimeMillis() / div * 100.0D;
		num %= 360.0D;
		num = Math.toRadians(num);
		num = Math.sin(num);
		return num;
	}

	public double range180(double rot) {
		rot = rot % 360;
		if (rot > 180) {
			rot -= 360;
		}
		return rot;
	}
	
	public double fixRotY(double rotY) {
		rotY = rotY % 360;
		while (rotY < 0) {
			rotY = rotY + 360;
		}
		return rotY;
	}
	
	public double fixRotX(double rotX) {
		if(rotX > 90) {
			rotX = 90;
		}
		if(rotX < -90) {
			rotX = -90;
		}
		return rotX;
	}

	public void makeLog(String logStr) {
		if (loggingEnabled) {
			logger.info(logStr);
		}
	}

	public static String compressString(String inputString) {
		byte[] inputBytes = inputString.getBytes(StandardCharsets.UTF_8);

		Deflater deflater = new Deflater();
		deflater.setInput(inputBytes);
		deflater.finish();

		byte[] compressedBytes = new byte[inputBytes.length];
		int compressedLength = deflater.deflate(compressedBytes);
		deflater.end();

		byte[] compressedData = new byte[compressedLength];
		System.arraycopy(compressedBytes, 0, compressedData, 0, compressedLength);

		String compressedString = base64encoder.encodeToString(compressedData);

		double compressionRatio = (double) compressedString.length() / inputBytes.length;

		if (compressionRatio > 1) {
			logger.info("compression ratio NOT GOOD: " + compressionRatio);
		}

		return compressedString;
	}

	public static String decompressString(String compressedString) {
		byte[] compressedBytes = base64decoder.decode(compressedString);

		Inflater inflater = new Inflater();
		inflater.setInput(compressedBytes);

		ByteArrayOutputStream outputStream = new ByteArrayOutputStream(compressedBytes.length);
		byte[] buffer = new byte[1024];

		try {
			while (!inflater.finished()) {
				int count = inflater.inflate(buffer);
				outputStream.write(buffer, 0, count);
			}
			try {
				outputStream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} catch (DataFormatException e) {
			e.printStackTrace();
		}

		inflater.end();

		byte[] decompressedData = outputStream.toByteArray();

		return new String(decompressedData, StandardCharsets.UTF_8);
	}
}
