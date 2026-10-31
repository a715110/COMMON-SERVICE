package com.dodaso.ecosystem.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.common.entity.DocumentConversion;
import com.dodaso.ecosystem.common.entity.LkpDocumentConversionStatus;
import com.dodaso.ecosystem.common.repository.DocumentConversionRepository;
import com.dodaso.ecosystem.common.repository.LkpDocumentConversionStatusRepository;
import com.dodaso.ecosystem.common.service.dto.BlobUploadResult;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

/** The converted PDF's page count is saved with the COMPLETED status. */
class DocumentConversionPageCountTest {

  private static byte[] pdf(int pages) throws Exception {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      for (int i = 0; i < pages; i++) {
        doc.addPage(new PDPage());
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void countsThePagesOfAPdf() throws Exception {
    assertThat(DocumentConversionService.countPages(pdf(3))).isEqualTo(3);
  }

  @Test
  void anUnreadablePdfHasNoPageCount() {
    assertThat(DocumentConversionService.countPages("not a pdf".getBytes())).isNull();
  }

  @Test
  void markCompletedSavesThePageCount() {
    DocumentConversionRepository conversions = mock(DocumentConversionRepository.class);
    LkpDocumentConversionStatusRepository statuses = mock(LkpDocumentConversionStatusRepository.class);
    DocumentConversion conversion = new DocumentConversion();
    when(conversions.findById(7L)).thenReturn(Optional.of(conversion));
    when(statuses.findByCode("COMPLETED")).thenReturn(Optional.of(new LkpDocumentConversionStatus()));
    DocumentConversionStatusUpdater updater = new DocumentConversionStatusUpdater(conversions, statuses);

    updater.markCompleted(7L, new BlobUploadResult("converted_7.pdf", "path", "container", "url", 10L,
        "application/pdf"), 4);

    assertThat(conversion.getPageCount()).isEqualTo(4);
    assertThat(conversion.getBlobPath()).isEqualTo("path");
    verify(conversions).save(conversion);
  }
}
