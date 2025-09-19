package com.g2806.undertaleextinct;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Undertale gun attack overlay with 4 sliders attack system
 */
public class UndertaleAttackGunOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("UndertaleAttackGunOverlay");
    private static final String MOD_ID = "undertaleextinct";
    
    // Texture identifiers
    private static final Identifier ATTACK_FRAME_TEXTURE = new Identifier(MOD_ID, "textures/gui/attack_frame.png");
    private static final Identifier ATTACK_SLIDER_TEXTURE = new Identifier(MOD_ID, "textures/gui/attack_slider.png");
    
    // Color cycling slider textures
    private static final Identifier RED_SLIDER_TEXTURE = new Identifier(MOD_ID, "textures/gui/red.png");
    private static final Identifier BLUE_SLIDER_TEXTURE = new Identifier(MOD_ID, "textures/gui/blue.png");
    private static final Identifier YELLOW_SLIDER_TEXTURE = new Identifier(MOD_ID, "textures/gui/yellow.png");
    private static final Identifier[] COLOR_SLIDER_TEXTURES = {RED_SLIDER_TEXTURE, BLUE_SLIDER_TEXTURE, YELLOW_SLIDER_TEXTURE};
    
    // Color cycling timing
    private static final long COLOR_CYCLE_DURATION = 100; // 0.1 seconds in milliseconds
    
    // Slash animation textures (1.png to 5.png)
    private static final int SLASH_FRAMES = 5;
    private static final List<Identifier> SLASH_TEXTURES = new ArrayList<>();
    
    static {
        // Initialize slash texture identifiers
        for (int i = 1; i <= SLASH_FRAMES; i++) {
            SLASH_TEXTURES.add(new Identifier(MOD_ID, "textures/gui/slash/" + i + ".png"));
        }
    }
    
    // Overlay state
    private boolean isActive = false;
    private boolean texturesLoaded = false;
    private boolean sliderTextureLoaded = false;
    private boolean colorSlidersLoaded = false;
    private boolean slashTexturesLoaded = false;
    
    // Fade-out effect
    private boolean fadingOut = false;
    private long fadeStartTime = 0;
    private static final long FADE_DURATION = 1000; // 1 second fade out
    
    // Color cycling state (only after click) - only one sequence per attack
    private boolean playingColorSequence = false;
    private long colorSequenceStartTime = 0;
    private int colorSequenceSliderIndex = -1; // Which slider index is playing the color sequence
    private static final long COLOR_SEQUENCE_TOTAL_DURATION = 300; // 0.3 seconds total (3 colors × 0.1s each)
    
    // Frame dimensions (smaller and thicker for better positioning)
    private static final int FRAME_WIDTH = 541;
    private static final int FRAME_HEIGHT = 107;
    
    // Slider dimensions (made thinner)
    private static final int SLIDER_WIDTH = 4;
    private static final int SLIDER_HEIGHT = 120;
    
    // 4 Slider system (spawning with 1 second delay, click affects current slider)
    private static final int NUM_SLIDERS = 4;
    private float[] sliderPositions = new float[NUM_SLIDERS]; // 0.0 = left, 1.0 = right
    private boolean[] sliderMoving = new boolean[NUM_SLIDERS]; // true = moving, false = stopped
    private boolean[] sliderFinished = new boolean[NUM_SLIDERS]; // true = clicked or reached end
    private boolean[] sliderSpawned = new boolean[NUM_SLIDERS]; // true = slider has spawned
    private int[] attackValues = new int[NUM_SLIDERS]; // 0-100 value for each slider
    private int currentSlider = 0; // Currently active slider (0-3)
    private int nextClickTarget = 0; // Next slider to be affected by click (sequential)
    private boolean allSlidersFinished = false;
    private boolean showResult = false;
    private int totalAttackValue = 0; // Sum of all 4 slider values
    private int currentAttackNumber = 0; // For numbered attacks (0 = regular gun attack)
    private long[] sliderSpawnTimes = new long[NUM_SLIDERS]; // When each slider should spawn
    private long attackStartTime = 0;
    private boolean wasMousePressed = false; // Track mouse state to prevent multiple clicks
    
    // Slider movement settings (configurable)
    private static final float DEFAULT_SLIDER_SPEED = 0.03f;
    private static final long SLIDER_SPAWN_DELAY = 1000; // 1 second in milliseconds
    
    // Slash animation state
    private boolean playingSlashAnimation = false;
    private int currentSlashFrame = 0;
    private int slashAnimationTicks = 0;
    private static final int SLASH_FRAME_DELAY = 3; // Ticks between frames
    
    public UndertaleAttackGunOverlay() {
        // Initialize all sliders
        resetSliders();
        
        // Load textures on initialization
        loadTextures();
        
        // Register tick event for slider movement, animation updates, and click detection
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (isActive) {
                if (!allSlidersFinished) {
                    updateCurrentSlider();
                    checkForClick(client);
                }
                updateSlashAnimation();
            }
        });
        
        // Register render callback
        HudRenderCallback.EVENT.register(this::renderOverlay);
        
    }
    
    private void resetSliders() {
        attackStartTime = System.currentTimeMillis();
        
        // Reset color sequence state
        playingColorSequence = false;
        colorSequenceStartTime = 0;
        colorSequenceSliderIndex = -1;
        
        // Reset fade-out state
        fadingOut = false;
        fadeStartTime = 0;
        
        for (int i = 0; i < NUM_SLIDERS; i++) {
            sliderPositions[i] = 0.0f;
            sliderMoving[i] = false;
            sliderFinished[i] = false;
            sliderSpawned[i] = false;
            attackValues[i] = 0;
            // Set spawn times: first at start time, others with 1 second delays
            sliderSpawnTimes[i] = attackStartTime + (i * SLIDER_SPAWN_DELAY);
        }
        
        currentSlider = 0;
        nextClickTarget = 0; // First click affects slider 0
        allSlidersFinished = false;
        showResult = false;
        totalAttackValue = 0;
        wasMousePressed = false;
        
        // Start first slider immediately
        sliderSpawned[0] = true;
        sliderMoving[0] = true;
    }
    
    private void updateCurrentSlider() {
        if (allSlidersFinished) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // Check for new sliders to spawn (only via timer, not clicks)
        for (int i = 0; i < NUM_SLIDERS; i++) {
            if (!sliderSpawned[i] && currentTime >= sliderSpawnTimes[i]) {
                sliderSpawned[i] = true;
                sliderMoving[i] = true;
                currentSlider = i;
            }
        }
        
        // Update all moving sliders
        for (int i = 0; i < NUM_SLIDERS; i++) {
            if (sliderMoving[i] && !sliderFinished[i] && sliderSpawned[i]) {
                float currentSpeed = ModConfig.getInstance().getAttackBarSpeedFloat();
                sliderPositions[i] += currentSpeed;
                
                // Check if slider reached the end
                if (sliderPositions[i] >= 1.0f) {
                    sliderPositions[i] = 1.0f;
                    sliderMoving[i] = false;
                    sliderFinished[i] = true;
                    
                    // Calculate attack value for this slider
                    calculateSliderValue(i);
                    
                    // Move focus to next slider if this one reached the end
                    if (i == currentSlider) {
                        currentSlider++;
                    }
                }
            }
        }
        
        // Check if all sliders are finished
        boolean allFinished = true;
        for (int i = 0; i < NUM_SLIDERS; i++) {
            if (!sliderSpawned[i] || (!sliderFinished[i] && sliderMoving[i])) {
                allFinished = false;
                break;
            }
        }
        
        if (allFinished) {
            finishAllSliders();
        }
    }
    
    
    private void calculateSliderValue(int sliderIndex) {
        calculateSliderValue(sliderIndex, false); // Default: not clicked, reached end
    }
    
    private void calculateSliderValue(int sliderIndex, boolean wasClicked) {
        // Convert slider position (0.0-1.0) to attack value (0-100)
        // Position 50 (middle) = Score 100 (best), Position 0/100 (edges) = Score 0 (worst)
        
        float positionValue = sliderPositions[sliderIndex] * 100; // 0.0 -> 0, 0.5 -> 50, 1.0 -> 100
        float distanceFrom50 = Math.abs(positionValue - 50.0f); // 0 at center, 50 at edges
        attackValues[sliderIndex] = Math.round(100 - (distanceFrom50 * 2)); // Linear scoring
        attackValues[sliderIndex] = Math.max(0, Math.min(100, attackValues[sliderIndex])); // Clamp to 0-100
        
        // Only start color sequence if slider was clicked (not if it reached the end)
        if (wasClicked) {
            startColorSequence(sliderIndex);
        }
    }
    
    
    private void finishAllSliders() {
        allSlidersFinished = true;
        
        // Calculate total attack value (sum of all 4 sliders)
        totalAttackValue = 0;
        for (int value : attackValues) {
            totalAttackValue += value;
        }
        
        // Start slash animation if any attack was successful
        if (totalAttackValue > 0) {
            startSlashAnimation();
        }
        
        showResult = true;
        
        // Send gun attack value to server for scoreboard
        sendGunAttackValueToServer(totalAttackValue);
        
        // Start fade-out after appropriate delay
        new Thread(() -> {
            try {
                if (totalAttackValue > 0) {
                    // Wait for slash animation to complete (5 frames * 3 ticks * 50ms per tick = ~0.75 seconds)
                    Thread.sleep(1500); // Extra buffer time
                } else {
                    // No animation, close faster for complete miss
                    Thread.sleep(1000);
                }
                startFadeOut();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    private void startSlashAnimation() {
        if (!slashTexturesLoaded) return;
        
        playingSlashAnimation = true;
        currentSlashFrame = 0;
        slashAnimationTicks = 0;
    }
    
    private void startColorSequence(int sliderIndex) {
        if (!colorSlidersLoaded) {
            LOGGER.debug("Color sliders not loaded, skipping color sequence");
            return;
        }
        
        // Only start a new sequence if one isn't already playing
        if (playingColorSequence) {
            LOGGER.debug("Color sequence already playing, skipping duplicate start");
            return;
        }
        
        playingColorSequence = true;
        colorSequenceStartTime = System.currentTimeMillis();
        colorSequenceSliderIndex = sliderIndex; // Track which slider is showing colors
        
        LOGGER.info("Started fast color sequence at slider {} (red→blue→yellow in 0.3s)", sliderIndex);
    }
    
    private void startFadeOut() {
        fadingOut = true;
        fadeStartTime = System.currentTimeMillis();
        
        // Start a thread to close overlay when fade is complete
        new Thread(() -> {
            try {
                Thread.sleep(FADE_DURATION + 100); // Wait for fade to complete plus buffer
                stopGunAttack();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    private float getCurrentAlpha() {
        if (!fadingOut) {
            return 1.0f; // Full opacity when not fading
        }
        
        long currentTime = System.currentTimeMillis();
        long elapsed = currentTime - fadeStartTime;
        
        if (elapsed >= FADE_DURATION) {
            return 0.0f; // Fully transparent when fade is complete
        }
        
        // Linear fade from 1.0 to 0.0 over FADE_DURATION
        return 1.0f - (float) elapsed / FADE_DURATION;
    }
    
    private int applyAlphaToColor(int color, float alpha) {
        // Extract ARGB components
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        
        // Apply alpha multiplier
        int newAlpha = (int) (a * alpha);
        
        // Reconstruct color with new alpha
        return (newAlpha << 24) | (r << 16) | (g << 8) | b;
    }
    
    private void updateSlashAnimation() {
        if (!playingSlashAnimation) return;
        
        slashAnimationTicks++;
        
        if (slashAnimationTicks >= SLASH_FRAME_DELAY) {
            slashAnimationTicks = 0;
            currentSlashFrame++;
            
            if (currentSlashFrame >= SLASH_FRAMES) {
                playingSlashAnimation = false;
                currentSlashFrame = 0;
            }
        }
    }
    
    private void loadTextures() {
        MinecraftClient client = MinecraftClient.getInstance();
        
        try {
            // Load attack frame texture
            client.getResourceManager().getResource(ATTACK_FRAME_TEXTURE);
            
            // Load slider texture
            client.getResourceManager().getResource(ATTACK_SLIDER_TEXTURE);
            sliderTextureLoaded = true;
            
            texturesLoaded = true;
        } catch (Exception e) {
            texturesLoaded = false;
        }
        
        // Load color slider textures
        try {
            colorSlidersLoaded = true;
            for (Identifier colorTexture : COLOR_SLIDER_TEXTURES) {
                client.getResourceManager().getResource(colorTexture);
            }
        } catch (Exception e) {
            colorSlidersLoaded = false;
        }
        
        // Load slash animation textures
        int loadedSlashTextures = 0;
        for (Identifier slashTexture : SLASH_TEXTURES) {
            try {
                client.getResourceManager().getResource(slashTexture);
                loadedSlashTextures++;
            } catch (Exception e) {
            }
        }
        
        if (loadedSlashTextures == SLASH_FRAMES) {
            slashTexturesLoaded = true;
        }
    }
    
    /**
     * Get the current slider texture for a specific slider index
     */
    private Identifier getCurrentSliderTexture(int sliderIndex) {
        // If slider is not spawned, don't render
        if (!sliderSpawned[sliderIndex]) {
            return null;
        }
        
        // If slider is finished (either clicked or reached end), check what to show
        if (sliderFinished[sliderIndex]) {
            // Only show color sequence on the slider that was clicked
            if (playingColorSequence && sliderIndex == colorSequenceSliderIndex) {
                // If color sliders not loaded, fallback to hiding slider
                if (!colorSlidersLoaded) {
                    return null; // Hide slider
                }
                
                long currentTime = System.currentTimeMillis();
                long elapsed = currentTime - colorSequenceStartTime;
                
                // If sequence is complete, hide slider immediately
                if (elapsed >= COLOR_SEQUENCE_TOTAL_DURATION) {
                    playingColorSequence = false; // Stop sequence
                    colorSequenceSliderIndex = -1; // Reset slider index
                    return null; // Hide slider instantly
                }
                
                // Cycle through colors every 0.1 seconds
                int colorIndex = (int) (elapsed / COLOR_CYCLE_DURATION) % COLOR_SLIDER_TEXTURES.length;
                return COLOR_SLIDER_TEXTURES[colorIndex];
            } else {
                // If not the clicked slider or no color sequence, hide slider immediately
                return null;
            }
        }
        
        // If slider is still moving, show normal slider
        return sliderTextureLoaded ? ATTACK_SLIDER_TEXTURE : ATTACK_SLIDER_TEXTURE;
    }
    
    public void startGunAttack() {
        if (isActive) {
            return;
        }
        isActive = true;
        currentAttackNumber = 0; // Regular gun attack
        resetSliders();
        
        if (!texturesLoaded) {
            loadTextures();
        }
    }
    
    public void startNumberedGunAttack(int attackNumber) {
        if (isActive) {
            return;
        }
        isActive = true;
        currentAttackNumber = attackNumber; // Set the attack number
        resetSliders();
        
        if (!texturesLoaded) {
            loadTextures();
        }
        
        LOGGER.info("Numbered gun attack overlay {} started - 4 sliders with sequential clicking!", attackNumber);
    }
    
    public void stopGunAttack() {
        isActive = false;
        resetSliders();
        playingSlashAnimation = false;
    }
    
    public boolean isActive() {
        return isActive;
    }
    
    public boolean handleClick() {
        if (!isActive || allSlidersFinished || nextClickTarget >= NUM_SLIDERS) {
            return false;
        }
        
        // Stop the next slider in sequence (0, 1, 2, 3) - only if it's spawned and moving
        if (sliderSpawned[nextClickTarget] && sliderMoving[nextClickTarget] && !sliderFinished[nextClickTarget]) {
            sliderMoving[nextClickTarget] = false;
            sliderFinished[nextClickTarget] = true;
            calculateSliderValue(nextClickTarget, true); // Clicked by user
            
            // Move to next slider for next click
            nextClickTarget++;
            
            return true;
        }
        
        return false;
    }
    
    private void checkForClick(MinecraftClient client) {
        // Detect single click (press, not hold) using mouse state tracking
        boolean isMousePressed = client.options.attackKey.isPressed();
        
        // Only register click on press (not while holding)
        if (isMousePressed && !wasMousePressed) {
            handleClick();
        }
        
        // Update mouse state for next frame
        wasMousePressed = isMousePressed;
    }
    
    private void sendGunAttackValueToServer(int gunAttackValue) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeInt(gunAttackValue);
            
            if (currentAttackNumber > 0) {
                // Send numbered gun attack packet
                buf.writeInt(currentAttackNumber);
                ClientPlayNetworking.send(UndertaleNetworking.NUMBERED_GUN_ATTACK_VALUE_PACKET, buf);
                LOGGER.info("Sent numbered gun attack {} value {} to server for scoreboard", currentAttackNumber, gunAttackValue);
            } else {
                // Send regular gun attack packet
                ClientPlayNetworking.send(UndertaleNetworking.GUN_ATTACK_VALUE_PACKET, buf);
                LOGGER.info("Sent gun attack value {} to server for scoreboard", gunAttackValue);
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to send gun attack value to server", e);
        }
    }
    
    private void renderOverlay(DrawContext context, float tickDelta) {
        if (!isActive || !texturesLoaded) {
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        
        // Get current alpha for fade effect
        float alpha = getCurrentAlpha();
        
        if (alpha <= 0.0f) {
            return; // Don't render if fully transparent
        }
        
        // Apply alpha blending
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        
        // Render attack frames (4 frames vertically stacked)
        renderGunAttackFrames(context, screenWidth, screenHeight);
        
        // Render sliders
        renderSliders(context, screenWidth, screenHeight);
        
        // Show result if finished
        if (showResult) {
            renderResult(context, screenWidth, screenHeight, alpha);
        }
        
        // Reset shader color and disable blending
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        
        // Render slash animation if playing (without fade effect)
        if (playingSlashAnimation) {
            renderSlashAnimation(context, screenWidth, screenHeight);
        }
    }
    
    private void renderGunAttackFrames(DrawContext context, int screenWidth, int screenHeight) {
        // Position single frame near hotbar (same as regular attack)
        int frameX = (screenWidth - FRAME_WIDTH) / 2;
        int frameY = screenHeight - FRAME_HEIGHT - 80; // Same positioning as regular attack
        
        
        // Draw frame texture
        context.drawTexture(ATTACK_FRAME_TEXTURE, frameX, frameY, 0, 0, FRAME_WIDTH, FRAME_HEIGHT, FRAME_WIDTH, FRAME_HEIGHT);
    }
    
    private void renderSliders(DrawContext context, int screenWidth, int screenHeight) {
        if (!sliderTextureLoaded && !colorSlidersLoaded) return;
        
        int frameX = (screenWidth - FRAME_WIDTH) / 2;
        int frameY = screenHeight - FRAME_HEIGHT - 80;
        
        for (int i = 0; i < NUM_SLIDERS; i++) {
            // Get the current slider texture for this specific slider (normal, color sequence, or null)
            Identifier currentSliderTexture = getCurrentSliderTexture(i);
            
            // If null, slider should be hidden (finished or not spawned)
            if (currentSliderTexture == null) {
                continue; // Skip rendering this slider
            }
            
            // Calculate slider position within the frame (same as regular attack slider)
            int sliderX = frameX + (int)(sliderPositions[i] * (FRAME_WIDTH - SLIDER_WIDTH));
            int sliderY = frameY + (FRAME_HEIGHT - SLIDER_HEIGHT) / 2; // All sliders at same position
            
            // Draw slider using normal or color cycling texture
            context.drawTexture(currentSliderTexture, sliderX, sliderY, 0, 0, SLIDER_WIDTH, SLIDER_HEIGHT, SLIDER_WIDTH, SLIDER_HEIGHT);
        }
    }
    
    private void renderResult(DrawContext context, int screenWidth, int screenHeight, float alpha) {
        // Show the gun attack result value with color coding
        String displayText;
        int textColor;
        
        if (totalAttackValue >= 360) { // 90% of 400 max
            displayText = "PERFECT! (" + totalAttackValue + "/400)";
            textColor = applyAlphaToColor(0xFF00FF00, alpha); // Green
        } else if (totalAttackValue >= 280) { // 70% of 400 max
            displayText = "Great! (" + totalAttackValue + "/400)";
            textColor = applyAlphaToColor(0xFF80FF80, alpha); // Light Green
        } else if (totalAttackValue >= 200) { // 50% of 400 max
            displayText = "Good! (" + totalAttackValue + "/400)";
            textColor = applyAlphaToColor(0xFFFFFF00, alpha); // Yellow
        } else if (totalAttackValue >= 100) { // 25% of 400 max
            displayText = "Okay (" + totalAttackValue + "/400)";
            textColor = applyAlphaToColor(0xFFFF8800, alpha); // Orange
        } else {
            displayText = "Miss... (" + totalAttackValue + "/400)";
            textColor = applyAlphaToColor(0xFFFF0000, alpha); // Red
        }
        
        // Show individual slider results
        StringBuilder detailText = new StringBuilder("Hits: ");
        for (int i = 0; i < NUM_SLIDERS; i++) {
            if (i > 0) detailText.append(", ");
            detailText.append(attackValues[i]);
        }
        
        // Position text centered above the attack frame (same as regular attack)
        int frameX = (screenWidth - FRAME_WIDTH) / 2;
        int frameY = screenHeight - FRAME_HEIGHT - 80;
        int textWidth = MinecraftClient.getInstance().textRenderer.getWidth(displayText);
        int textX = (screenWidth - textWidth) / 2;
        int textY = frameY - 30;
        
        context.drawText(MinecraftClient.getInstance().textRenderer, displayText, textX, textY, textColor, true);
        
        // Center the detail text as well
        int detailWidth = MinecraftClient.getInstance().textRenderer.getWidth(detailText.toString());
        int detailX = (screenWidth - detailWidth) / 2;
        int detailColor = applyAlphaToColor(0xFFFFFFFF, alpha); // White text with alpha
        context.drawText(MinecraftClient.getInstance().textRenderer, detailText.toString(), detailX, textY + 12, detailColor, true);
    }
    
    private void renderSlashAnimation(DrawContext context, int screenWidth, int screenHeight) {
        if (currentSlashFrame < 0 || currentSlashFrame >= SLASH_FRAMES) return;
        
        // Get current slash texture
        Identifier currentSlashTexture = SLASH_TEXTURES.get(currentSlashFrame);
        
        // Render in center of screen - 2x smaller than original size
        int slashWidth = 100;  // Half the original size
        int slashHeight = 100; // Half the original size
        int slashX = (screenWidth - slashWidth) / 2;
        int slashY = (screenHeight - slashHeight) / 2;
        
        // Draw the current slash frame
        context.drawTexture(currentSlashTexture, slashX, slashY, 0, 0, slashWidth, slashHeight, slashWidth, slashHeight);
    }
    
}