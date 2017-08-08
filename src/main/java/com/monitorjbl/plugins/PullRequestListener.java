package com.monitorjbl.plugins;

import com.atlassian.bitbucket.build.BuildStatusSetEvent;
import com.atlassian.bitbucket.commit.Commit;
import com.atlassian.bitbucket.event.pull.PullRequestParticipantStatusUpdatedEvent;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestMergeRequest;
import com.atlassian.bitbucket.pull.PullRequestSearchRequest;
import com.atlassian.bitbucket.pull.PullRequestState;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequestImpl;
import com.atlassian.event.api.EventListener;
import com.monitorjbl.plugins.AsyncProcessor.PRHarmonyTaskRequest;
import com.monitorjbl.plugins.AsyncProcessor.TaskContext;
import com.monitorjbl.plugins.config.Config;

public class PullRequestListener {
  private static final String BUCKET = "AUTOMERGE_PR";
  public static final int MAX_COMMITS = 1048576;

  private final RegexUtils regexUtils;
  private final AsyncProcessor asyncProcessor;

  public PullRequestListener(AsyncProcessor asyncProcessor, RegexUtils regexUtils) {
    this.regexUtils = regexUtils;
    this.asyncProcessor = asyncProcessor;
  }

  @EventListener
  public void prApprovalListener(PullRequestParticipantStatusUpdatedEvent event) {
    asyncProcessor.dispatch(BUCKET, new PRHarmonyTaskRequest(event.getPullRequest()), (ctx) -> {
      ctx.securityService.withPermission(Permission.ADMIN, "Automerge check (PR approval)").call(() -> {
        automergePullRequest(ctx, event.getPullRequest());
        return null;
      });
    });
  }

  @EventListener
  public void buildStatusListener(BuildStatusSetEvent event) {
    asyncProcessor.dispatch(BUCKET, new PRHarmonyTaskRequest(), (ctx) -> {
      ctx.securityService.withPermission(Permission.ADMIN, "Automerge check (PR approval)").call(() -> {
        PullRequest pr = findPRByCommitId(ctx, event.getCommitId());
        if(pr != null) {
          automergePullRequest(ctx, pr);
        }
        return null;
      });
    });


  }

  void automergePullRequest(TaskContext ctx, PullRequest pr) {
    Repository repo = pr.getToRef().getRepository();
    Config config = ctx.configDao.getConfigForRepo(repo.getProject().getKey(), repo.getSlug());
    String toBranch = regexUtils.formatBranchName(pr.getToRef().getId());
    String fromBranch = regexUtils.formatBranchName(pr.getFromRef().getId());

    if((regexUtils.match(config.getAutomergePRs(), toBranch) || regexUtils.match(config.getAutomergePRsFrom(), fromBranch)) &&
        !regexUtils.match(config.getBlockedPRs(), toBranch) && ctx.prService.canMerge(repo.getId(), pr.getId()).canMerge()) {
      ctx.securityService.withPermission(Permission.ADMIN, "Automerging pull request").call(() ->
          ctx.prService.merge(new PullRequestMergeRequest.Builder(pr).build()));
    }
  }

  PullRequest findPRByCommitId(TaskContext ctx, String commitId) {
    int start = 0;
    Page<PullRequest> requests = null;
    while(requests == null || requests.getSize() > 0) {
      requests = ctx.prService.search(new PullRequestSearchRequest.Builder()
          .state(PullRequestState.OPEN)
          .build(), new PageRequestImpl(start, 10));
      for(PullRequest pr : requests.getValues()) {
        Page<Commit> commits = ctx.prService.getCommits(pr.getToRef().getRepository().getId(), pr.getId(), new PageRequestImpl(0, MAX_COMMITS));
        for(Commit c : commits.getValues()) {
          if(c.getId().equals(commitId)) {
            return pr;
          }
        }
      }
      start += 10;
    }
    return null;
  }
}
