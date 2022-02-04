/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.edu.learning.stepik;

import com.jetbrains.edu.learning.EduSettings;
import com.jetbrains.edu.learning.settings.LoginOptions;
import com.jetbrains.edu.learning.stepik.api.StepikConnector;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.HyperlinkEvent;

public class StepikOptions extends LoginOptions<StepikUser> {
  @Nullable
  @Override
  public StepikUser getCurrentAccount() {
    return EduSettings.getInstance().getUser();
  }

  @Override
  public void setCurrentAccount(@Nullable StepikUser stepikUser) {
    EduSettings.getInstance().setUser(stepikUser);
    if (stepikUser != null) {
      StepikConnector.getInstance().notifyUserLoggedIn();
    } else {
      StepikConnector.getInstance().notifyUserLoggedOut();
    }
  }

  @NotNull
  protected LoginListener createAuthorizeListener() {
    return new LoginListener() {

      @Override
      protected void authorize(HyperlinkEvent e) {
        Runnable[] postLoginActions = new Runnable[2];
        postLoginActions[0] = () -> {
          StepikUser user = EduSettings.getInstance().getUser();
          setLastSavedAccount(user);
        };
        postLoginActions[1] = () -> updateLoginLabels();

        StepikConnector.getInstance().doAuthorize(postLoginActions);
      }

      private void showDialog() {
        OAuthDialog dialog = new OAuthDialog();
        if (dialog.showAndGet()) {
          final StepikUser user = EduSettings.getInstance().getUser();
          setLastSavedAccount(user);
          updateLoginLabels();
        }
      }
    };
  }

  @Nls
  @Override
  public String getDisplayName() {
    return "Stepik";
  }

  @NotNull
  @Override
  protected String profileUrl(@NotNull StepikUser account) {
    return StepikUtils.getProfileUrl(account);
  }
}
