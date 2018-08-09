package com.shackspacehosting.engineering.pvmanager.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Controller
public class HomeController {
    private final Logger logger = LoggerFactory.getLogger(getClass());

	@Value("${openid.connect.logoutUri}")
	private String logoutUri;

	@RequestMapping("/authentication")
    @ResponseBody
//@Secured("GROUP_SHACKADMINS")
    public final Authentication authentication() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        final String username = authentication.getName();
        logger.info(username);
        return authentication;
    }

    @RequestMapping("/logout")
    @ResponseBody
    public final String logout(HttpServletRequest request, HttpServletResponse response) {
        final String username = SecurityContextHolder.getContext().getAuthentication().getName();


		String authorization = request.getHeader("Authorization");
		if (authorization != null && authorization.contains("Bearer")) {
			String tokenId = authorization.substring("Bearer".length() + 1);


			//tokenServices.revokeToken(tokenId);
		}

		try {
			response.sendRedirect(logoutUri);
		} catch (IOException e) {
			e.printStackTrace();
		}
        return "Logging out " + username;
    }

}
