package com.g2806.undertaleextinct;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * Undertale attack overlay that renders on the HUD (similar to AnimationPlayer)
 */
public class UndertaleAttackOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("UndertaleAttackOverlay");
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
    
    
    // Overlay state
    private boolean isActive = false;
    private boolean texturesLoaded = false;
    private boolean sliderTextureLoaded = false;
    private boolean colorSlidersLoaded = false;
    
    // Fade-out effect
    private boolean fadingOut = false;
    private long fadeStartTime = 0;
    private static final long FADE_DURATION = 1000; // 1 second fade out
    
    // Frame dimensions (smaller and thicker for better positioning)
    private static final int FRAME_WIDTH = 541;  // Even smaller width
    private static final int FRAME_HEIGHT = 107; // Thicker height
    
    // Slider dimensions (made thinner)
    private static final int SLIDER_WIDTH = 4;  // Thinner slider (was 14)
    private static final int SLIDER_HEIGHT = 120;
    
    // Slider movement state
    private float sliderPosition = 0.0f; // 0.0 = left, 1.0 = right
    private float sliderSpeed = 0.03f;   // Speed of movement (1.5x faster)
    private boolean sliderMoving = true; // true = still moving, false = stopped
    private boolean sliderFinished = false; // true = reached end or clicked
    private int attackValue = 0; // 0-100 value based on click position
    private boolean showResult = false; // show the result value
    private int currentAttackNumber = 0; // For numbered attacks (0 = regular attack)
    
    // Color cycling state (only after click)
    private boolean playingColorSequence = false;
    private long colorSequenceStartTime = 0;
    private static final long COLOR_SEQUENCE_TOTAL_DURATION = 300; // 0.3 seconds total (3 colors × 0.1s each)
    
    
    public UndertaleAttackOverlay() {
        registerHudRender();
        registerEvents();
    }
    
    private void registerHudRender() {
        // Register HUD render callback (similar to AnimationPlayer)
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (isActive) {
                renderAttackOverlay(drawContext, tickDelta);
            }
        });
    }
    
    private void registerEvents() {
        // Register tick events for slider animation, slash animation, and input checking
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (isActive) {
                if (sliderTextureLoaded && sliderMoving) {
                    updateSliderPosition();
                }
                checkForClick(client);
            }
        });
    }
    
    private void checkForClick(MinecraftClient client) {
        // Check if left mouse button was clicked during slider movement
        if (sliderMoving && client.mouse.wasLeftButtonClicked()) {
            sliderMoving = false;
            sliderFinished = true;
            calculateAttackValue(true); // Clicked by user
        }
    }
    
    public void onClick() {
        // External click handler for UndertaleAttackHandler
        if (sliderMoving) {
            sliderMoving = false;
            sliderFinished = true;
            calculateAttackValue(true); // Clicked by user
        }
    }
    
    private void updateSliderPosition() {
        // Move slider only left to right (once)
        if (sliderMoving) {
            sliderPosition += sliderSpeed;
            if (sliderPosition >= 1.0f) {
                sliderPosition = 1.0f;
                sliderMoving = false; // Stop moving
                sliderFinished = true; // Finished at end
                calculateAttackValue(); // Calculate value when it reaches the end
                LOGGER.info("Slider reached end automatically, attack value: {}", attackValue);
            }
        }
    }
    
    
    private void calculateAttackValue() {
        calculateAttackValue(false); // Default: not clicked, reached end
    }
    
    private void calculateAttackValue(boolean wasClicked) {
        // Convert slider position (0.0-1.0) to attack value (0-100)
        // Position 50 (middle) = Score 100 (best), Position 0/100 (edges) = Score 0 (worst)
        
        // Convert slider position (0.0-1.0) to position value (0-100)
        float positionValue = sliderPosition * 100; // 0.0 -> 0, 0.5 -> 50, 1.0 -> 100
        
        // Calculate distance from perfect center (50)
        float distanceFrom50 = Math.abs(positionValue - 50.0f); // 0 at center, 50 at edges
        
        // Convert distance to score: 0 distance = 100 score, 50 distance = 0 score
        attackValue = Math.round(100 - (distanceFrom50 * 2)); // Linear scoring
        attackValue = Math.max(0, Math.min(100, attackValue)); // Clamp to 0-100
        
        // Only start color sequence if slider was clicked (not if it reached the end)
        if (wasClicked) {
            startColorSequence();
        }
        
        
        showResult = true;
        
        // Send attack value to server for scoreboard
        sendAttackValueToServer(attackValue);
        
        // Start fade-out after delay
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                startFadeOut();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    
    private void startColorSequence() {
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
        
        LOGGER.info("Started fast color sequence (red→blue→yellow in 0.3s)");
    }
    
    private void startFadeOut() {
        fadingOut = true;
        fadeStartTime = System.currentTimeMillis();
        
        // Start a thread to close overlay when fade is complete
        new Thread(() -> {
            try {
                Thread.sleep(FADE_DURATION + 100); // Wait for fade to complete plus buffer
                stopAttack();
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
    
    private void sendAttackValueToServer(int attackValue) {
        try {
            // Create packet with attack value
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeInt(attackValue);
            
            if (currentAttackNumber > 0) {
                // Send numbered attack packet
                buf.writeInt(currentAttackNumber);
                ClientPlayNetworking.send(UndertaleNetworking.NUMBERED_ATTACK_VALUE_PACKET, buf);
                LOGGER.info("Sent numbered attack {} value {} to server for scoreboard", currentAttackNumber, attackValue);
            } else {
                // Send regular attack packet
                ClientPlayNetworking.send(UndertaleNetworking.ATTACK_VALUE_PACKET, buf);
                LOGGER.info("Sent attack value {} to server for scoreboard", attackValue);
            }
            
        } catch (Exception e) {
            LOGGER.warn("Failed to send attack value to server", e);
        }
    }
    
    private void loadTextures() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getResourceManager() == null) {
            LOGGER.warn("ResourceManager not available for texture loading");
            return;
        }
        
        try {
            // Check if attack frame texture exists
            var frameResource = client.getResourceManager().getResource(ATTACK_FRAME_TEXTURE);
            texturesLoaded = frameResource.isPresent();
            
            // Check if slider texture exists
            var sliderResource = client.getResourceManager().getResource(ATTACK_SLIDER_TEXTURE);
            sliderTextureLoaded = sliderResource.isPresent();
            
            // Check color slider textures
            colorSlidersLoaded = true;
            for (Identifier colorTexture : COLOR_SLIDER_TEXTURES) {
                var colorResource = client.getResourceManager().getResource(colorTexture);
                if (colorResource.isEmpty()) {
                    colorSlidersLoaded = false;
                    break;
                }
            }
            
            
            if (texturesLoaded) {
                LOGGER.info("Attack frame texture loaded for overlay: {}", ATTACK_FRAME_TEXTURE);
            } else {
                LOGGER.info("Attack frame texture NOT found for overlay: {}, using fallback", ATTACK_FRAME_TEXTURE);
            }
            
            if (sliderTextureLoaded) {
                LOGGER.info("Attack slider texture loaded for overlay: {}", ATTACK_SLIDER_TEXTURE);
            } else {
                LOGGER.info("Attack slider texture NOT found for overlay: {}, using fallback", ATTACK_SLIDER_TEXTURE);
            }
            
            if (colorSlidersLoaded) {
                LOGGER.info("All color slider textures loaded for cycling effect");
            } else {
                LOGGER.info("Some color slider textures missing, using default slider");
            }
            
            
        } catch (Exception e) {
            LOGGER.warn("Failed to load attack textures for overlay", e);
            texturesLoaded = false;
            sliderTextureLoaded = false;
            colorSlidersLoaded = false;
        }
    }
    
    /**
     * Get the current slider texture - normal until finished, then color sequence or null
     */
    private Identifier getCurrentSliderTexture() {
        // If slider is finished (either clicked or reached end), check what to show
        if (sliderFinished) {
            // If playing color sequence (clicked), show colors
            if (playingColorSequence) {
                // If color sliders not loaded, fallback to hiding slider
                if (!colorSlidersLoaded) {
                    return null; // Hide slider
                }
                
                long currentTime = System.currentTimeMillis();
                long elapsed = currentTime - colorSequenceStartTime;
                
                // If sequence is complete, hide slider immediately
                if (elapsed >= COLOR_SEQUENCE_TOTAL_DURATION) {
                    playingColorSequence = false; // Stop sequence
                    return null; // Hide slider instantly
                }
                
                // Cycle through colors every 0.5 seconds
                int colorIndex = (int) (elapsed / COLOR_CYCLE_DURATION) % COLOR_SLIDER_TEXTURES.length;
                return COLOR_SLIDER_TEXTURES[colorIndex];
            } else {
                // If not playing color sequence (reached end), hide slider immediately
                return null;
            }
        }
        
        // If slider is still moving, show normal slider
        return sliderTextureLoaded ? ATTACK_SLIDER_TEXTURE : ATTACK_SLIDER_TEXTURE;
    }
    
    private void renderAttackOverlay(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        
        // Get current alpha for fade effect
        float alpha = getCurrentAlpha();
        
        if (alpha <= 0.0f) {
            return; // Don't render if fully transparent
        }
        
        // Get screen dimensions
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        
        // Position closer to hotbar
        int frameX = (screenWidth - FRAME_WIDTH) / 2;
        int frameY = screenHeight - FRAME_HEIGHT - 30; // Only 30 pixels above bottom (closer to hotbar)
        
        // Apply alpha blending
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        
        if (texturesLoaded) {
            // Render attack frame texture
            context.drawTexture(ATTACK_FRAME_TEXTURE, frameX, frameY, 0, 0, FRAME_WIDTH, FRAME_HEIGHT, FRAME_WIDTH, FRAME_HEIGHT);
        }
        
        // Render sliding attack meter
        renderSlider(context, frameX, frameY, client);
        
        // Show step text or result
        String displayText;
        int textColor;
        
        if (showResult) {
            // Show the attack result value (higher score is better now)
            if (attackValue >= 90) {
                displayText = "PERFECT! (" + attackValue + ")";
                textColor = applyAlphaToColor(0xFF00FF00, alpha); // Green
            } else if (attackValue >= 70) {
                displayText = "Great! (" + attackValue + ")";
                textColor = applyAlphaToColor(0xFF80FF80, alpha); // Light Green
            } else if (attackValue >= 50) {
                displayText = "Good! (" + attackValue + ")";
                textColor = applyAlphaToColor(0xFFFFFF00, alpha); // Yellow
            } else if (attackValue >= 25) {
                displayText = "Okay (" + attackValue + ")";
                textColor = applyAlphaToColor(0xFFFF8800, alpha); // Orange
            } else {
                displayText = "Miss... (" + attackValue + ")";
                textColor = applyAlphaToColor(0xFFFF0000, alpha); // Red
            }
        } else {
            // No text when slider is moving or waiting
            displayText = null;
            textColor = 0xFFFFFFFF; // White (unused)
        }
        
        // Draw text centered above the frame (only if there's text to show)
        if (displayText != null) {
            int textWidth = client.textRenderer.getWidth(displayText);
            int textX = (screenWidth - textWidth) / 2;
            int textY = frameY - 30;
            
            context.drawText(client.textRenderer, displayText, textX, textY, textColor, true);
        }
        
        // Reset shader color and disable blending
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        
        
        LOGGER.debug("Rendering attack overlay at position ({}, {})", frameX, frameY);
    }
    
    private int applyFadeToColor(int color, float alpha) {
        int a = (int) (((color >> 24) & 0xFF) * alpha);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    
    private void renderTextureWithFade(DrawContext context, Identifier texture, int x, int y, int width, int height, float fadeAlpha) {
        // For now, DrawContext doesn't have built-in alpha blending for textures
        // This is a placeholder - in practice, you'd need to use a shader or matrix transform
        // For demonstration, we'll just render normally when alpha > 0.1, otherwise skip
        if (fadeAlpha > 0.1f) {
            context.drawTexture(texture, x, y, 0, 0, width, height, width, height);
        }
    }
    
    private void renderSlider(DrawContext context, int frameX, int frameY, MinecraftClient client) {
        // Calculate slider position - moves across the middle of the frame
        int sliderAreaWidth = FRAME_WIDTH - SLIDER_WIDTH; // Available area for slider movement
        int sliderX = frameX + (int)(sliderPosition * sliderAreaWidth);
        
        // Position slider in the middle (vertically) of the frame
        // Since slider height (120) is taller than frame height (90), center it vertically
        int sliderY = frameY + (FRAME_HEIGHT / 2) - (SLIDER_HEIGHT / 2);
        
        // Get the current slider texture (normal or color sequence)
        Identifier currentSliderTexture = getCurrentSliderTexture();
        
        // If null, slider should be hidden (color sequence finished)
        if (currentSliderTexture == null) {
            return; // Don't render slider
        }
        
        if (sliderTextureLoaded || colorSlidersLoaded) {
            // Render the slider texture (normal or color sequence)
            context.drawTexture(currentSliderTexture, sliderX, sliderY, 0, 0, SLIDER_WIDTH, SLIDER_HEIGHT, SLIDER_WIDTH, SLIDER_HEIGHT);
        } else {
            // Fallback: red rectangle slider
            int sliderColor = 0xFFFF0000; // Red
            context.fill(sliderX, sliderY, sliderX + SLIDER_WIDTH, sliderY + SLIDER_HEIGHT, sliderColor);
        }
        
        LOGGER.debug("Rendering slider at position ({}, {}) with slider position {}", sliderX, sliderY, sliderPosition);
    }
    
    
    /**
     * Start showing the attack overlay
     */
    public void startAttack() {
        if (!texturesLoaded || !sliderTextureLoaded) {
            loadTextures();
        }
        
        // Reset all states
        sliderPosition = 0.0f;
        sliderMoving = true;
        sliderFinished = false;
        attackValue = 0;
        showResult = false;
        currentAttackNumber = 0; // Regular attack
        
        // Reset color sequence state
        playingColorSequence = false;
        colorSequenceStartTime = 0;
        
        isActive = true;
        LOGGER.info("Attack overlay started - slider moves left to right, click to stop!");
    }
    
    /**
     * Start showing the numbered attack overlay
     */
    public void startNumberedAttack(int attackNumber) {
        if (!texturesLoaded || !sliderTextureLoaded) {
            loadTextures();
        }
        
        // Reset all states
        sliderPosition = 0.0f;
        sliderMoving = true;
        sliderFinished = false;
        attackValue = 0;
        showResult = false;
        currentAttackNumber = attackNumber; // Set the attack number
        
        // Reset color sequence state
        playingColorSequence = false;
        colorSequenceStartTime = 0;
        
        isActive = true;
        LOGGER.info("Numbered attack overlay {} started - slider moves left to right, click to stop!", attackNumber);
    }
    
    /**
     * Stop showing the attack overlay
     */
    public void stopAttack() {
        isActive = false;
        sliderMoving = false;
        sliderFinished = false;
        sliderPosition = 0.0f;
        showResult = false;
        attackValue = 0;
        
        // Reset fade-out state
        fadingOut = false;
        fadeStartTime = 0;
        
        LOGGER.info("Attack overlay stopped");
    }
    
    /**
     * Check if overlay is currently active
     */
    public boolean isActive() {
        return isActive;
    }
    
    /**
     * Toggle the overlay on/off
     */
    public void toggle() {
        if (isActive) {
            stopAttack();
        } else {
            startAttack();
        }
    }
}