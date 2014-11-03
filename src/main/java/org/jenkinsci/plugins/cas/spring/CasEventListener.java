package org.jenkinsci.plugins.cas.spring;

import hudson.tasks.Mailer;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.Map;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Properties;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.acegisecurity.GrantedAuthority;
import org.acegisecurity.GrantedAuthorityImpl;
import org.acegisecurity.context.SecurityContextHolder;
import org.acegisecurity.userdetails.User;
import org.jenkinsci.plugins.cas.spring.security.CasAuthentication;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.cas.authentication.CasAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Listener for successful CAS authentication events, that maps Spring Security
 * Authentication to Acegi Security and syncs attributes with the corresponding Jenkins User.
 * 
 * @author Fabien Crespel <fabien@crespel.net>
 */
public class CasEventListener implements ApplicationListener {
  	private static final Logger LOGGER = Logger.getLogger(CasEventListener.class.getName());

	public static final String DEFAULT_FULL_NAME_ATTRIBUTE = "cn";
	public static final String DEFAULT_EMAIL_ATTRIBUTE = "mail";

	private String fullNameAttribute = DEFAULT_FULL_NAME_ATTRIBUTE;
	private String emailAttribute = DEFAULT_EMAIL_ATTRIBUTE;

	/**
	 * Handle an application event.
	 * @param event the event to respond to
	 */
	public void onApplicationEvent(ApplicationEvent event) {
		if (event instanceof InteractiveAuthenticationSuccessEvent) {
			onSuccessfulAuthentication(((InteractiveAuthenticationSuccessEvent)event).getAuthentication());
		}
	}

	/**
	 * Successful authentication event handler.
	 * @param authentication the successful authentication object
	 */
	protected void onSuccessfulAuthentication(Authentication authentication) {
		if (authentication instanceof CasAuthenticationToken) {
			CasAuthenticationToken casToken = (CasAuthenticationToken) authentication;
			try {
				copyToAcegiContext(casToken);
				syncUserAttributes(casToken);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * Map a Spring Security CAS authentication token into the Acegi SecurityContext.
	 * @param casToken CAS authentication token
	 */
	protected void copyToAcegiContext(CasAuthenticationToken casToken) {
		// Map granted authorities
		ArrayList<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();

		int i = 0;
		for (org.springframework.security.core.GrantedAuthority authority : casToken.getAuthorities()) {
			authorities.add(new GrantedAuthorityImpl(authority.getAuthority()));
		}
		
		// Map user
		org.springframework.security.core.userdetails.User sourceUser = (org.springframework.security.core.userdetails.User) casToken.getUserDetails();

		// ASU::
		LOGGER.log(Level.INFO, "CAS login: {0}", sourceUser.getUsername());
		FileInputStream in = null;
		try {
			Properties groupProps = new Properties();
			in = new FileInputStream(new File(Jenkins.getInstance().getRootDir().getPath(), "casgroups.properties"));
			groupProps.load(in);
			if (groupProps.containsKey(sourceUser.getUsername())) {
			  	String groups = groupProps.getProperty(sourceUser.getUsername());
				String[] args = new String[] { sourceUser.getUsername(), groups };
				LOGGER.log(Level.INFO, "CAS groups for {0} are {1}", args);
				for (String group : groups.split(",")) {
					authorities.add(new GrantedAuthorityImpl(group));
				}
			}
		} catch (FileNotFoundException e) {
			LOGGER.log(Level.WARNING, "CAS group properties not found");
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, "CAS group properties Exception");
		} finally {
	 		if (in != null) {
				try {
	  				in.close();
				} catch (IOException e) {
				}
			}
		}

		User user = new User(sourceUser.getUsername(), sourceUser.getPassword(), sourceUser.isEnabled(), sourceUser.isAccountNonExpired(), sourceUser.isCredentialsNonExpired(), sourceUser.isAccountNonLocked(), authorities.toArray(new GrantedAuthority[0]));

		// Build a CasAuthentication object
		CasAuthentication authentication = new CasAuthentication(casToken.getKeyHash(), user, casToken.getCredentials(), authorities.toArray(new GrantedAuthority[0]), user, casToken.getAssertion());

		// Fill the Acegi security context
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	/**
	 * Sync user attributes with a CAS authentication token.
	 * @param casToken CAS authentication token
	 * @throws IOException
	 */
	protected void syncUserAttributes(CasAuthenticationToken casToken) throws IOException {
		if (casToken.getAssertion() == null ||
			casToken.getAssertion().getPrincipal() == null ||
			casToken.getAssertion().getPrincipal().getAttributes() == null) {
			// No attributes to sync with
			return;
		}

		// Retrieve or create the Jenkins user
		hudson.model.User user = hudson.model.User.get(casToken.getName());

		// Sync the full name
		String fullName = getAttributeValue(casToken, getFullNameAttribute());
		if (fullName != null) {
			user.setFullName(fullName);
		}

		// Sync the email address
		String email = getAttributeValue(casToken, getEmailAttribute());
		if (email != null) {
			user.addProperty(new Mailer.UserProperty(email));
		}

		// Save the user to disk
		user.save();
	}

	/**
	 * Retrieve an attribute's value from a CAS authentication token.
	 * @param authToken CAS authentication token
	 * @param attributeName attribute name
	 * @return attribute value or null if not found
	 */
	@SuppressWarnings("rawtypes")
	protected String getAttributeValue(CasAuthenticationToken authToken, String attributeName) {
		if (authToken != null && authToken.getAssertion() != null &&
			authToken.getAssertion().getPrincipal() != null &&
			authToken.getAssertion().getPrincipal().getAttributes() != null &&
			attributeName != null) {

			Map attributes = authToken.getAssertion().getPrincipal().getAttributes();
			Object attribute = attributes.get(attributeName);
			if (attribute != null) {
				if (attribute instanceof Collection) {
					return ((Collection<String>)attribute).iterator().next();
				} else {
					return attribute.toString();
				}
			}
		}
		return null;
	}

	/**
	 * Get the full name attribute name.
	 * @return full name attribute name.
	 */
	public String getFullNameAttribute() {
		return fullNameAttribute;
	}

	/**
	 * Set the full name attribute name.
	 * @param fullNameAttribute full name attribute name.
	 */
	public void setFullNameAttribute(String fullNameAttribute) {
		if (fullNameAttribute == null) {
			this.fullNameAttribute = DEFAULT_FULL_NAME_ATTRIBUTE;
		} else {
			this.fullNameAttribute = fullNameAttribute;
		}
	}

	/**
	 * Get the email address attribute name.
	 * @return email address attribute name.
	 */
	public String getEmailAttribute() {
		return emailAttribute;
	}

	/**
	 * Set the email address attribute name.
	 * @param emailAttribute email address attribute name.
	 */
	public void setEmailAttribute(String emailAttribute) {
		if (emailAttribute == null) {
			this.emailAttribute = DEFAULT_EMAIL_ATTRIBUTE;
		} else {
			this.emailAttribute = emailAttribute;
		}
	}
}
