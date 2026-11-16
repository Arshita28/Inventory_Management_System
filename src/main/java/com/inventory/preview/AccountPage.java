package com.inventory.preview;

import com.inventory.security.*;
import java.util.List;

/** Account administration is rendered only for an authenticated Admin. */
final class AccountPage {
    private AccountPage() { }
    static String render(List<User> users, String csrf) {
        StringBuilder out = new StringBuilder("<section id='users' data-view='users' class='workspace-view panel ops-panel'><div class='panel-title'><h2>People &amp; access</h2><p>Create individual accounts and control who can use the workspace. Account changes end that person's active sessions.</p></div><div class='account-list'>");
        for (User user : users) {
            out.append("<form class='account-row' method='post' action='/users/update'>").append(hidden("csrf", csrf)).append(hidden("userId", user.id().toString()))
                    .append("<strong>").append(InventoryPage.html(user.username())).append("</strong><label>Role<select name='role'>");
            roles(out, user.role());
            out.append("</select></label><label>Status<select name='active'><option value='true'").append(user.active() ? " selected" : "").append(">Active</option><option value='false'")
                    .append(!user.active() ? " selected" : "").append(">Disabled</option></select></label><label>New password (optional)<input name='password' type='password' autocomplete='new-password' minlength='15' maxlength='128' placeholder='Leave unchanged'></label><button type='submit'>Save account</button></form>");
        }
        out.append("</div><h3>Add an account</h3><form method='post' action='/users/create'>").append(hidden("csrf", csrf))
                .append("<div class='form-grid'><label>Username<input name='username' required minlength='3' maxlength='40' pattern='[A-Za-z0-9._-]+' autocomplete='off'></label><label>Role<select name='role'>");
        roles(out, Role.STAFF);
        return out.append("</select></label><label>Initial password<input name='password' type='password' required minlength='15' maxlength='128' autocomplete='new-password'></label></div><p class='muted'>Use 15–128 characters. The last active Admin cannot be disabled or changed to another role.</p><button type='submit'>Create account</button></form></section>").toString();
    }
    private static String hidden(String key, String value) { return "<input type='hidden' name='" + key + "' value='" + InventoryPage.html(value) + "'>"; }
    private static void roles(StringBuilder out, Role selected) {
        for (Role role : Role.values()) out.append("<option value='").append(role.name()).append("'").append(role == selected ? " selected" : "").append(">").append(role.label()).append("</option>");
    }
}
