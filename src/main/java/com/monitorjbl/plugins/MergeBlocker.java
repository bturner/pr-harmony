package com.monitorjbl.plugins;

import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestParticipant;
import com.atlassian.bitbucket.pull.PullRequestParticipantStatus;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.pull.MergeRequest;
import com.atlassian.bitbucket.scm.pull.MergeRequestCheck;
import com.google.common.base.Joiner;
import com.monitorjbl.plugins.config.Config;
import com.monitorjbl.plugins.config.ConfigDao;

import javax.annotation.Nonnull;
import java.util.Set;

public class MergeBlocker implements MergeRequestCheck {
  private final ConfigDao configDao;
  private final UserUtils userUtils;
  private final RegexUtils regexUtils;

  public MergeBlocker(ConfigDao configDao, UserUtils userUtils, RegexUtils regexUtils) {
    this.configDao = configDao;
    this.userUtils = userUtils;
    this.regexUtils = regexUtils;
  }

  @Override
  public void check(@Nonnull MergeRequest mergeRequest) {
    PullRequest pr = mergeRequest.getPullRequest();
    Repository repo = pr.getToRef().getRepository();
    final Config config = configDao.getConfigForRepo(repo.getProject().getKey(), repo.getSlug());

    String branch = regexUtils.formatBranchName(pr.getToRef().getId());
    if (regexUtils.match(config.getBlockedPRs(), branch)) {
      mergeRequest.veto("Pull Request Blocked", "Pull requests have been disabled for branch [" + branch + "]");
    } else {
      PullRequestApproval approval = new PullRequestApproval(config, userUtils);
      if (!approval.isPullRequestApproved(pr)) {
        Set<String> missing = approval.missingRevieiwersNames(pr);
        mergeRequest.veto("Required reviewers must approve", (config.getRequiredReviews() - approval.seenReviewers(pr).size()) +
            " more approvals required from the following users: " + Joiner.on(", ").join(missing));
      } else {
        Boolean needsWork = config.getBlockMergeIfPrNeedsWork();
        final Boolean blockAutoMergeBecausePrNeedsWork = needsWork != null && needsWork && needsWork(pr);

        if (blockAutoMergeBecausePrNeedsWork) {
          mergeRequest.veto("Needs work", "PR marked as Needs Work from reviewer(s)");
        }
      }
    }
  }

  private boolean needsWork(final PullRequest pr) {
    boolean needsWork = false;

    for (PullRequestParticipant reviewer : pr.getReviewers()) {
      if (reviewer.getStatus() == PullRequestParticipantStatus.NEEDS_WORK) {
        needsWork = true;
        break;
      }
    }

    return needsWork;
  }
}
