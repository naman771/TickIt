package com.tickit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tickit.controller.DeveloperPageController;
import com.tickit.controller.RootPageController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PageRoutingTest {
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var root = new RootPageController();
    ReflectionTestUtils.setField(root, "organizerHost", "organizers.tickit.site");
    ReflectionTestUtils.setField(root, "developerHost", "developers.tickit.site");
    mvc = MockMvcBuilders.standaloneSetup(root, new DeveloperPageController()).build();
  }

  @Test
  void organizerCanonicalPathServesItsIndex() throws Exception {
    mvc.perform(get("/organizer")).andExpect(redirectedUrl("/organizer/"));
    mvc.perform(get("/organizer/"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/organizer/index.html"));
  }

  @Test
  void canonicalRedirectsPreserveAuthenticationAndApplicationQuery() throws Exception {
    for (String workspace : new String[] {"organizer", "developer"}) {
      mvc.perform(get("/" + workspace + "?__clerk_handshake=temporary&view=events"))
          .andExpect(redirectedUrl("/" + workspace + "/?__clerk_handshake=temporary&view=events"));
    }
  }

  @Test
  void organizerHostUsesCanonicalDirectorySoRelativeAssetsResolve() throws Exception {
    mvc.perform(get("/?__clerk_handshake=temporary").with(request -> {
          request.setServerName("organizers.tickit.site");
          return request;
        }))
        .andExpect(redirectedUrl("/organizer/?__clerk_handshake=temporary"));
  }

  @Test
  void customerAndDeveloperKeepTheirOwnEntryPages() throws Exception {
    mvc.perform(get("/")).andExpect(forwardedUrl("/index.html"));
    mvc.perform(get("/developer/")).andExpect(forwardedUrl("/developer/index.html"));
    mvc.perform(get("/").with(request -> {
          request.setServerName("developers.tickit.site");
          return request;
        }))
        .andExpect(forwardedUrl("/developer/index.html"));
  }
}
