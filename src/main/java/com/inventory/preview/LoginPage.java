package com.inventory.preview;

import com.inventory.security.Role;
import com.inventory.security.auth.DemoPersonas;
import java.util.List;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Landing intro and sign-in share the black content area. Submitted passwords are never reflected; demo fixture credentials are intentionally public. */
final class LoginPage {
    private final String template;
    LoginPage() throws IOException {
        try (var in = getClass().getResourceAsStream("/landing.html")) {
            if (in == null) throw new IOException("Landing page missing");
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    String render(String csrf, boolean setup, boolean open, String error, String username, String role, boolean loggedOut, List<DemoPersonas.Persona> personas) {
        StringBuilder form = new StringBuilder("<section class='auth-panel' id='login' aria-labelledby='login-title'><a class='login-back' href='/' aria-label='Back to landing page'><svg viewBox='0 0 24 24' aria-hidden='true'><path d='M19 12H5m7-7-7 7 7 7'/></svg><span>Back to landing</span></a><p class='eyebrow'>VOLTEDGE ELECTRONICS / ACCOUNT ACCESS</p><h2 id='login-title'>")
                .append(setup ? "Start with an Admin account." : "Welcome back.").append("</h2><p class='auth-description'>")
                .append(setup ? "Set up your first Admin. You can then add Manager and Staff accounts." : personas.isEmpty() ? "Choose your role and log in to your workspace." : "Choose a role and a demo persona. Then click Login.").append("</p>");
        if (!error.isEmpty()) form.append("<p class='auth-message error' role='alert'>").append(InventoryPage.html(error)).append("</p>");
        if (loggedOut) form.append("<p class='auth-message' role='status'>You have been logged out.</p>");
        form.append("<form method='post' action='").append(setup ? "/auth/setup" : "/auth/login").append("'>")
                .append("<input type='hidden' name='csrf' value='").append(InventoryPage.html(csrf)).append("'>")
                .append("<fieldset class='login-roles'><legend>Log in as</legend>");
        for (Role choice : Role.values()) {
            form.append("<label><input type='radio' name='role' value='").append(choice.name()).append("'")
                    .append((setup ? choice == Role.ADMIN : choice.name().equals(role)) ? " checked" : "")
                    .append(setup && choice != Role.ADMIN ? " disabled" : "")
                    .append("><span>").append(choice.label()).append("</span></label>");
        }
        form.append("</fieldset>");
        if (!personas.isEmpty()) {
            form.append("<div class='persona-heading'><span>Demo personas</span><span>Click to autofill</span></div><div class='persona-grid' aria-label='Choose a demo persona'>");
            for (var persona : personas) {
                form.append("<button type='button' class='persona-card' data-persona-role='").append(persona.role().name())
                        .append("' data-persona-name='").append(InventoryPage.html(persona.name()))
                        .append("' data-persona-username='").append(InventoryPage.html(persona.username()))
                        .append("' data-persona-password='").append(InventoryPage.html(persona.password()))
                        .append("' aria-pressed='false'").append(persona.role().name().equals(role) ? "" : " hidden").append("><span class='persona-avatar' aria-hidden='true'>")
                        .append(persona.initials()).append("</span><span class='persona-info'><strong>").append(persona.name())
                        .append("</strong><small>").append(persona.title()).append("</small></span></button>");
            }
            form.append("</div><p class='persona-status' role='status' aria-live='polite'>Select a persona to fill your login details.</p>");
        }
        form.append("<label class='auth-label' for='login-username'>Username</label><input class='auth-input' id='login-username' name='username' required minlength='3' maxlength='40' autocomplete='username' autocapitalize='none' spellcheck='false' value='")
                .append(InventoryPage.html(username)).append("'><label class='auth-label' for='login-password'>Password</label><div class='password-field'><input class='auth-input' id='login-password' name='password' type='password' required maxlength='128' autocomplete='")
                .append(setup ? "new-password" : "current-password").append("' spellcheck='false' autocapitalize='none'").append(setup ? " minlength='15' aria-describedby='password-help'" : "")
                .append("><button type='button' class='password-toggle' aria-controls='login-password' aria-label='Show password' hidden>Show</button></div>");
        if (setup) form.append("<p class='auth-help' id='password-help'>Use a unique password of 15–128 characters.</p>");
        form.append("<button class='get-started auth-submit' type='submit'>").append(setup ? "Create Admin &amp; log in" : "Login").append("</button></form><p class='auth-help'>")
                .append(setup ? "First-time setup · available only until the first account is created." : personas.isEmpty() ? "Need an account or password reset? Contact your Admin." : "Demo accounts · credentials are filled for this local preview.")
                .append("</p></section>");
        return template.replace("{{authState}}", open ? "auth-open" : "")
                .replace("{{loginPanel}}", form.toString());
    }
}
