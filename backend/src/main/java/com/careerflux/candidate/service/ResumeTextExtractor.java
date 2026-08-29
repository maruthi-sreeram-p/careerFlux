package com.careerflux.candidate.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.careerflux.common.error.BadRequestException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pulls plain text out of an uploaded resume. Deliberately supports only the
 * three formats that actually turn up, and validates by content rather than by
 * trusting the filename or the browser-supplied content type.
 */
@Component
public class ResumeTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(ResumeTextExtractor.class);
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46};
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    private static final int MAX_TEXT_LENGTH = 200_000;

    public Result extract(byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw new BadRequestException("That file is empty.");
        }
        Format format = detect(content, filename);
        try {
            String text = switch (format) {
                case PDF -> fromPdf(content);
                case DOCX -> fromDocx(content);
                case TEXT -> new String(content, StandardCharsets.UTF_8);
            };
            return new Result(normalize(text), format.name());
        } catch (IOException | RuntimeException ex) {
            log.warn("Could not read {} as {}: {}", filename, format, ex.getMessage());
            throw new BadRequestException("That file could not be read. Try a PDF, DOCX or plain text resume.");
        }
    }

    private Format detect(byte[] content, String filename) {
        if (startsWith(content, PDF_MAGIC)) {
            return Format.PDF;
        }
        if (startsWith(content, ZIP_MAGIC)) {
            // DOCX is a ZIP container; a ZIP that is not a Word document fails on open below.
            return Format.DOCX;
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return Format.TEXT;
        }
        if (looksLikeText(content)) {
            return Format.TEXT;
        }
        throw new BadRequestException("Upload a PDF, DOCX or plain text resume.");
    }

    private boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean looksLikeText(byte[] content) {
        int sample = Math.min(content.length, 512);
        int printable = 0;
        for (int i = 0; i < sample; i++) {
            byte b = content[i];
            if (b == '\n' || b == '\r' || b == '\t' || (b >= 0x20 && b != 0x7F)) {
                printable++;
            }
        }
        return printable > sample * 0.9;
    }

    private String fromPdf(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                throw new BadRequestException("That PDF is password protected. Upload an unprotected copy.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private String fromDocx(byte[] content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replace("\r\n", "\n")
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
        return cleaned.length() > MAX_TEXT_LENGTH ? cleaned.substring(0, MAX_TEXT_LENGTH) : cleaned;
    }

    private enum Format {
        PDF, DOCX, TEXT
    }

    public record Result(String text, String format) {
    }
}
