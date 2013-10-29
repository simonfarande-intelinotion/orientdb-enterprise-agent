package com.orientechnologies.orient.monitor.event;

import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

import javax.mail.MessagingException;
import javax.mail.internet.AddressException;

import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.monitor.event.metric.OEventLogExecutor;
import com.orientechnologies.orient.server.OServerMain;
import com.orientechnologies.orient.server.plugin.mail.OMailPlugin;
import com.orientechnologies.orient.server.plugin.mail.OMailProfile;

@EventConfig(when = "LogWhen", what = "MailWhat")
public class OEventLogMailExecutor extends OEventLogExecutor {

	@Override
	public void execute(ODocument source, ODocument when, ODocument what) {

		// pre-conditions
		if (canExecute(source, when)) {
			mailEvent(what);
		}
	}

	public void mailEvent(ODocument what) {
		final OMailPlugin mail = OServerMain.server().getPluginByClass(
				OMailPlugin.class);

		final Map<String, Object> configuration = new HashMap<String, Object>();
		OMailProfile prof = new OMailProfile();
		prof.put("mail.smtp.user", "");
		prof.put("mail.smtp.password", "");
		String subject = what.field("subject");
		String address = what.field("toAddress");
		String cc = what.field("cc");
		String bcc = what.field("bcc");
		String body = what.field("body");
		configuration.put("to", address);
		configuration.put("profile", "default");
		configuration.put("message", body);
		configuration.put("cc", cc);
		configuration.put("bcc", bcc);
		configuration.put("subject", subject);

		try {
			mail.send(configuration);
		} catch (AddressException e) {
			e.printStackTrace();
		} catch (MessagingException e) {
			e.printStackTrace();
		} catch (ParseException e) {
			e.printStackTrace();
		}

	}

}
