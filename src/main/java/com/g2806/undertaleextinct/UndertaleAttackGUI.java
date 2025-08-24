package com.g2806.undertaleextinct;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;

/**
 * Undertale-style attack GUI with sliding meter
 */
public class UndertaleAttackGUI extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("UndertaleAttackGUI");
    private static final String MOD_ID = "undertaleextinct";
    
    // Attack meter configuration
    private static final int METER_WIDTH = 300;
    private static final int METER_HEIGHT = 20;
    private static final int SLIDER_WIDTH = 6;
    private static final int SLIDER_HEIGHT = 30;
    
    // Texture identifiers
    private static final Identifier ATTACK_FRAME_TEXTURE = new Identifier(MOD_ID, "textures/gui/attack_frame.png");
    private static final Identifier METER_BG_TEXTURE = new Identifier(MOD_ID, "textures/gui/attack_meter_bg.png");
    private static final Identifier METER_PERFECT_TEXTURE = new Identifier(MOD_ID, "textures/gui/attack_meter_perfect.png");
    private static final Identifier METER_GOOD_TEXTURE = new Identifier(MOD_ID, "textures/gui/attack_meter_good.png");
    private static final Identifier SLIDER_TEXTURE = new Identifier(MOD_ID, "textures/gui/attack_slider.png");
    
    // Texture loading state
    private boolean texturesLoaded = false;
    
    // Meter state
    private float meterPosition = 0.0f; // 0.0 to 1.0
    private float meterSpeed = 0.02f; // Speed of the sliding meter
    private boolean meterDirection = true; // true = right, false = left
    private boolean isActive = true;
    private boolean hasClicked = false;
    
    // Attack result
    private int attackValue = 0; // 0-100, where 50 is perfect
    private long resultDisplayTime = 0;
    private boolean showResult = false;
    
    // GUI textures (we'll use simple colors for now, can be replaced with images later)
    private static final int BACKGROUND_COLOR = 0x88000000; // Semi-transparent black
    private static final int METER_BG_COLOR = 0xFF333333; // Dark gray
    private static final int METER_BORDER_COLOR = 0xFFFFFFFF; // White
    private static final int SLIDER_COLOR = 0xFFFF0000; // Red slider
    private static final int PERFECT_ZONE_COLOR = 0xFF00FF00; // Green perfect zone
    private static final int GOOD_ZONE_COLOR = 0xFFFFFF00; // Yellow good zone
    
    // Perfect zone (around 50%)
    private static final float PERFECT_ZONE_START = 0.45f;
    private static final float PERFECT_ZONE_END = 0.55f;
    private static final float GOOD_ZONE_START = 0.35f;
    private static final float GOOD_ZONE_END = 0.65f;
    
    public UndertaleAttackGUI() {
        super(Text.literal("Attack!"));
    }
    
    @Override
    protected void init() {
        super.init();
        // Reset state when GUI opens
        meterPosition = 0.0f;
        meterDirection = true;
        isActive = true;
        hasClicked = false;
        showResult = false;
        attackValue = 0;
        
        // Load textures
        loadAttackTextures();
    }
    
    private void loadAttackTextures() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getResourceManager() == null) {
            LOGGER.warn("ResourceManager not available for texture loading");
            return;
        }
        
        try {
            // For Step 1, only check if attack frame exists
            var frameResource = client.getResourceManager().getResource(ATTACK_FRAME_TEXTURE);
            texturesLoaded = frameResource.isPresent();
            
            if (texturesLoaded) {
                LOGGER.info("Attack frame texture loaded successfully: {}", ATTACK_FRAME_TEXTURE);
            } else {
                LOGGER.info("Attack frame texture NOT found at: {}, using fallback colors", ATTACK_FRAME_TEXTURE);
            }
            
        } catch (Exception e) {
            LOGGER.warn("Failed to load attack frame texture: {}", ATTACK_FRAME_TEXTURE, e);
            texturesLoaded = false;
        }
    }
    
    @Override
    public void tick() {
        super.tick();
        
        if (isActive && !hasClicked) {
            // Update meter position
            if (meterDirection) {
                meterPosition += meterSpeed;
                if (meterPosition >= 1.0f) {
                    meterPosition = 1.0f;
                    meterDirection = false;
                }
            } else {
                meterPosition -= meterSpeed;
                if (meterPosition <= 0.0f) {
                    meterPosition = 0.0f;
                    meterDirection = true;
                }
            }
        }
        
        // Handle result display timeout
        if (showResult && System.currentTimeMillis() - resultDisplayTime > 2000) {
            this.close();
        }
    }
    
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (texturesLoaded) {
            renderWithTextures(context, mouseX, mouseY, delta);
        } else {
            renderWithColors(context, mouseX, mouseY, delta);
        }
        
        super.render(context, mouseX, mouseY, delta);
    }
    
    private void renderWithTextures(DrawContext context, int mouseX, int mouseY, float delta) {
        // Step 1: Just show the attack frame above the hotbar
        // Use the actual texture dimensions: 561x127
        int frameWidth = 561;
        int frameHeight = 127;
        int frameX = (this.width - frameWidth) / 2;
        int frameY = this.height - frameHeight - 80; // 80 pixels above bottom (above hotbar)
        
        // Draw only the attack frame for now
        context.drawTexture(ATTACK_FRAME_TEXTURE, frameX, frameY, 0, 0, frameWidth, frameHeight, frameWidth, frameHeight);
        
        // Show step 1 text
        renderResultText(context, frameY);
        
        // TODO: Later we'll add the meter, zones, and slider here step by step
    }
    
    private void renderWithColors(DrawContext context, int mouseX, int mouseY, float delta) {
        // Step 1: Just show a simple frame above the hotbar (fallback when no textures)
        int frameWidth = 561;  // Match texture dimensions
        int frameHeight = 127; // Match texture dimensions
        int frameX = (this.width - frameWidth) / 2;
        int frameY = this.height - frameHeight - 80; // 80 pixels above bottom (above hotbar)
        
        // Draw simple frame border and background
        context.fill(frameX - 2, frameY - 2, frameX + frameWidth + 2, frameY + frameHeight + 2, METER_BORDER_COLOR);
        context.fill(frameX, frameY, frameX + frameWidth, frameY + frameHeight, BACKGROUND_COLOR);
        
        // Show step 1 text
        renderResultText(context, frameY);
        
        // TODO: Later we'll add the meter, zones, and slider here step by step
    }
    
    private void renderResultText(DrawContext context, int frameY) {
        // For now, just show simple instruction text
        String displayText = "Attack Frame - Step 1";
        int textColor = 0xFFFFFFFF; // White
        
        // Draw text centered above the frame
        int textWidth = this.textRenderer.getWidth(displayText);
        int textX = (this.width - textWidth) / 2;
        int textY = frameY - 20; // Above the frame
        context.drawText(this.textRenderer, displayText, textX, textY, textColor, true);
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isActive && !hasClicked) { // Left click
            hasClicked = true;
            isActive = false;
            
            // Calculate attack value based on meter position (0-100)
            // Perfect zone (45-55%) gives values around 50
            // Convert meter position (0.0-1.0) to attack value (0-100)
            attackValue = Math.round(Math.abs(meterPosition - 0.5f) * 200);
            attackValue = 100 - attackValue; // Invert so 50 (center) gives 100, edges give 0
            attackValue = Math.max(0, Math.min(100, attackValue)); // Clamp to 0-100
            
            // Adjust the calculation to make 50 the perfect score
            if (meterPosition >= PERFECT_ZONE_START && meterPosition <= PERFECT_ZONE_END) {
                // Perfect zone - score between 45-55
                float perfectProgress = (meterPosition - PERFECT_ZONE_START) / (PERFECT_ZONE_END - PERFECT_ZONE_START);
                attackValue = Math.round(45 + perfectProgress * 10);
            } else if (meterPosition >= GOOD_ZONE_START && meterPosition <= GOOD_ZONE_END) {
                // Good zone - score between 25-75 (excluding perfect zone)
                if (meterPosition < PERFECT_ZONE_START) {
                    float goodProgress = (meterPosition - GOOD_ZONE_START) / (PERFECT_ZONE_START - GOOD_ZONE_START);
                    attackValue = Math.round(25 + goodProgress * 20);
                } else {
                    float goodProgress = (meterPosition - PERFECT_ZONE_END) / (GOOD_ZONE_END - PERFECT_ZONE_END);
                    attackValue = Math.round(55 + goodProgress * 20);
                }
            } else {
                // Miss zone - score between 0-25
                if (meterPosition < GOOD_ZONE_START) {
                    attackValue = Math.round(meterPosition / GOOD_ZONE_START * 25);
                } else {
                    float missProgress = (meterPosition - GOOD_ZONE_END) / (1.0f - GOOD_ZONE_END);
                    attackValue = Math.round(25 - missProgress * 25);
                }
            }
            
            // Play sound effect
            MinecraftClient.getInstance().getSoundManager().play(
                PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f)
            );
            
            // Show result
            showResult = true;
            resultDisplayTime = System.currentTimeMillis();
            
            LOGGER.info("Attack completed - Position: {}, Value: {}", meterPosition, attackValue);
            
            return true;
        }
        
        return super.mouseClicked(mouseX, mouseY, button);
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Allow ESC to close the GUI
        if (keyCode == 256) { // ESC key
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    
    @Override
    public boolean shouldPause() {
        return false; // Don't pause the game
    }
    
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
    
    @Override
    public void renderBackground(DrawContext context) {
        // Don't render the default screen background - we want to see the game behind
    }
    
    /**
     * Get the last attack result value (0-100)
     */
    public int getLastAttackValue() {
        return attackValue;
    }
    
    /**
     * Check if the attack was perfect (45-55 range)
     */
    public boolean wasPerfectAttack() {
        return attackValue >= 45 && attackValue <= 55;
    }
    
    /**
     * Check if the attack was good (25-75 range)
     */
    public boolean wasGoodAttack() {
        return attackValue >= 25 && attackValue <= 75;
    }
}