package com.remoteroles.pipeline.api;

import java.util.Map;

import com.remoteroles.pipeline.newsletter.EmployerDigestSender;
import com.remoteroles.pipeline.newsletter.MailBudget;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The employer digest's two manual handles, under {@code /admin} and so behind
 * {@link AdminTokenFilter}: a preview of one employer's week sent to another
 * address, and the week's run on demand. Separate from {@link AdminController}
 * so that adding them did not mean another argument on its constructor.
 */
@RestController
@RequestMapping("/admin/employer-digest")
public class EmployerDigestController {

    private final EmployerDigestSender sender;
    private final MailBudget budget;

    public EmployerDigestController(EmployerDigestSender sender, MailBudget budget) {
        this.sender = sender;
        this.budget = budget;
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestParam("to") String to, @RequestParam("employer") long employerId) {
        boolean sent = sender.sendPreview(to, employerId);
        if (sent) budget.record(MailBudget.Tier.TRANSACTIONAL);
        return Map.of("sent", sent);
    }

    @PostMapping("/send")
    public Map<String, Object> send() {
        return Map.of("sent", sender.sendWeek());
    }
}
