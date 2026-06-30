package com.bvisionry.common.pdf;

import com.bvisionry.common.exception.ReportGenerationException;
import com.lowagie.text.pdf.BaseFont;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;

/**
 * Shared rendering pipeline for every branded PDF export (member results,
 * org insights, team insights).
 *
 * <p>It centralises the three concerns that were previously duplicated across
 * each report service:
 * <ul>
 *   <li>registering the brand typeface (Inter) on the renderer,</li>
 *   <li>exposing the brand imagery (logo, mark, watermark) to every template as
 *       base64 {@code data:} URIs — natively decoded by Flying Saucer's
 *       {@code ITextUserAgent}, so they render identically whether the app runs
 *       from an exploded class path or a packaged Spring Boot jar,</li>
 *   <li>the Thymeleaf &rarr; XHTML &rarr; PDF conversion itself.</li>
 * </ul>
 *
 * <p>Templates pull the shared assets from the {@code brandLogo},
 * {@code brandMark} and {@code brandWatermark} context variables, which this
 * renderer injects automatically — individual services never touch fonts or
 * imagery.
 */
@Component
@Slf4j
public class PdfRenderer {

    /** Family name templates reference in CSS (font-family: 'Inter', ...). */
    private static final String BRAND_FONT_FAMILY = "Inter";

    /**
     * Brand weights registered under {@link #BRAND_FONT_FAMILY}. Flying Saucer
     * reads each file's weight/style from its TrueType tables, so CSS
     * font-weight (400/500/600/700) resolves to the matching face.
     */
    private static final String[] BRAND_FONT_RESOURCES = {
            "fonts/Inter-Regular.ttf",
            "fonts/Inter-Medium.ttf",
            "fonts/Inter-SemiBold.ttf",
            "fonts/Inter-Bold.ttf",
    };

    private final TemplateEngine templateEngine;

    // Brand assets are immutable and class-path bundled — resolve them once.
    private final String brandLogo;
    private final String brandMark;
    private final String brandWatermark;

    public PdfRenderer(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
        this.brandLogo = loadImageDataUri("images/bvisionry-logo.png");
        this.brandMark = loadImageDataUri("images/bvisionry-mark.png");
        this.brandWatermark = loadImageDataUri("images/bvisionry-mark-watermark.png");
    }

    /**
     * Render a Thymeleaf template to PDF bytes, injecting the shared brand
     * assets into the supplied context first.
     *
     * @param templateName the Thymeleaf template (without extension)
     * @param context      the populated rendering context
     * @return the generated PDF as a byte array
     * @throws ReportGenerationException if rendering fails
     */
    public byte[] renderTemplate(String templateName, Context context) {
        context.setVariable("brandLogo", brandLogo);
        context.setVariable("brandMark", brandMark);
        context.setVariable("brandWatermark", brandWatermark);
        String html = templateEngine.process(templateName, context);
        return renderHtml(html, templateName);
    }

    private byte[] renderHtml(String html, String templateName) {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            registerBrandFonts(renderer);
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(os);
            log.info("Rendered PDF from template '{}' ({} bytes)", templateName, os.size());
            return os.toByteArray();
        } catch (Exception e) {
            log.error("Failed to render PDF from template '{}': {}", templateName, e.getMessage(), e);
            throw new ReportGenerationException(
                    "PDF generation failed for template '" + templateName + "'", e);
        }
    }

    private void registerBrandFonts(ITextRenderer renderer) {
        for (String resourcePath : BRAND_FONT_RESOURCES) {
            try {
                var resource = getClass().getClassLoader().getResource(resourcePath);
                if (resource != null) {
                    renderer.getFontResolver().addFont(
                            resource.toExternalForm(), BRAND_FONT_FAMILY,
                            BaseFont.IDENTITY_H, true, null);
                } else {
                    log.warn("Brand font resource not found: {}", resourcePath);
                }
            } catch (Exception e) {
                log.warn("Failed to register brand font {}: {}", resourcePath, e.getMessage());
            }
        }
    }

    private String loadImageDataUri(String resourcePath) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.warn("Brand image resource not found: {}", resourcePath);
                return "";
            }
            byte[] bytes = in.readAllBytes();
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.warn("Failed to load brand image {}: {}", resourcePath, e.getMessage());
            return "";
        }
    }
}
