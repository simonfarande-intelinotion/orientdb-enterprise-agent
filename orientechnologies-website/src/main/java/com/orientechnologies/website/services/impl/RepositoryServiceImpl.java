package com.orientechnologies.website.services.impl;

import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.website.OrientDBFactory;
import com.orientechnologies.website.helper.SecurityHelper;
import com.orientechnologies.website.model.schema.HasIssue;
import com.orientechnologies.website.model.schema.HasLabel;
import com.orientechnologies.website.model.schema.HasMilestone;
import com.orientechnologies.website.model.schema.dto.*;
import com.orientechnologies.website.model.schema.dto.web.IssueDTO;
import com.orientechnologies.website.repository.EventRepository;
import com.orientechnologies.website.repository.IssueRepository;
import com.orientechnologies.website.repository.RepositoryRepository;
import com.orientechnologies.website.repository.UserRepository;
import com.orientechnologies.website.services.IssueService;
import com.orientechnologies.website.services.RepositoryService;
import com.tinkerpop.blueprints.impls.orient.OrientGraph;
import com.tinkerpop.blueprints.impls.orient.OrientVertex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Created by Enrico Risa on 21/10/14.
 */
@Service
public class RepositoryServiceImpl implements RepositoryService {

  @Autowired
  private OrientDBFactory        dbFactory;
  @Autowired
  protected RepositoryRepository repositoryRepository;

  @Autowired
  protected IssueRepository      issueRepository;

  @Autowired
  protected IssueService         issueService;

  @Autowired
  protected UserRepository       userRepo;

  @Autowired
  protected EventRepository      eventRepository;

  protected RepositoryService    githubService;

  @PostConstruct
  protected void init() {
    githubService = new RepositoryServiceGithub(this);
  }

  @Override
  public Repository createRepo(String name, String description) {
    Repository repo = new Repository();
    repo.setName(name);
    repo.setDescription(description);
    return repositoryRepository.save(repo);
  }

  @Override
  public void createIssue(Repository repository, Issue issue) {
    createHasIssueRelationship(repository, issue);
  }

  @Transactional
  @Override
  public Issue openIssue(Repository repository, IssueDTO issue) {
    if (Boolean.TRUE.equals(issue.getConfidential())) {
      return createPrivateIssue(repository, issue);
    } else {
      return githubService.openIssue(repository, issue);
    }
  }

  @Transactional
  @Override
  public Issue patchIssue(Issue original, IssueDTO issue) {

    if (Boolean.TRUE.equals(original.getConfidential())) {
      return patchPrivateIssue(original, issue, true);
    } else {
      patchPrivateIssue(original, issue, false);
      return githubService.patchIssue(original, issue);
    }
  }

  private Issue patchPrivateIssue(Issue original, IssueDTO issue, boolean skipGithub) {
    Repository r = original.getRepository();

    if (issue.getVersion() != null) {
      if (original.getVersion() == null || original.getVersion().getNumber() != issue.getVersion()) {
        handleVersion(r, original, issue.getVersion());
      }
    }
    if (issue.getMilestone() != null) {
      if (original.getMilestone() == null || original.getMilestone().getNumber() != issue.getMilestone()) {
        handleMilestone(r, original, issue.getMilestone());
      }
    }
    if (skipGithub) {
      if (issue.getState() != null) {
        if (!original.getState().equals(issue.getState())) {
          original = issueService.changeState(original, issue.getState(), null, true);
        }
      }
    }
    return original;
  }

  private Issue createPrivateIssue(Repository repository, IssueDTO issue) {
    String assignee = issue.getAssignee();
    Integer milestoneId = issue.getMilestone();
    Integer versionId = issue.getVersion();
    Issue issueDomain = new Issue();
    issueDomain.setTitle(issue.getTitle());
    issueDomain.setBody(issue.getBody());
    issueDomain.setCreatedAt(new Date());
    issueDomain.setState(Issue.IssueState.OPEN.toString());
    issueDomain = issueRepository.save(issueDomain);
    createIssue(repository, issueDomain);
    OUser user = SecurityHelper.currentUser();
    issueService.changeUser(issueDomain, user);
    handleAssignee(issueDomain, assignee);
    handleMilestone(repository, issueDomain, milestoneId);
    handleVersion(repository, issueDomain, versionId);
    handleLabels(repository, issueDomain, issue.getLabels());
    return issueDomain;
  }

  protected void handleVersion(Repository repository, Issue issue, Integer milestone) {
    if (milestone != null) {
      Milestone m = repositoryRepository.findMilestoneByRepoAndName(repository.getName(), milestone);
      if (m != null) {
        issueService.changeVersion(issue, m);
      }
    }
  }

  private void handleLabels(Repository repository, Issue issue, List<String> labelsList) {

    List<Label> labels = new ArrayList<Label>();
    for (String label : labelsList) {
      Label l = repositoryRepository.findLabelsByRepoAndName(repository.getName(), label);
      if (l != null) {
        labels.add(l);
      }
    }
    issueService.changeLabels(issue, labels, false);
  }

  protected void handleMilestone(Repository repository, Issue issue, Integer milestone) {

    if (milestone != null) {
      Milestone m = repositoryRepository.findMilestoneByRepoAndName(repository.getName(), milestone);
      if (m != null) {
        issueService.changeMilestone(issue, m, null, true);
      }
    }
  }

  private void handleAssignee(Issue issue, String assigneeName) {
    OUser assignee = userRepo.findUserByLogin(assigneeName);
    if (assignee != null) {
      issueService.assign(issue, assignee, null, true);
    }
  }

  @Override
  public void addLabel(Repository repo, Label label) {

    OrientGraph graph = dbFactory.getGraph();
    OrientVertex orgVertex = graph.getVertex(new ORecordId(repo.getId()));
    OrientVertex devVertex = graph.getVertex(new ORecordId(label.getId()));
    orgVertex.addEdge(HasLabel.class.getSimpleName(), devVertex);
  }

  @Override
  public void addMilestone(Repository repo, Milestone milestone) {
    OrientGraph graph = dbFactory.getGraph();

    OrientVertex orgVertex = graph.getVertex(new ORecordId(repo.getId()));
    OrientVertex devVertex = graph.getVertex(new ORecordId(milestone.getId()));
    orgVertex.addEdge(HasMilestone.class.getSimpleName(), devVertex);
  }

  private void createHasIssueRelationship(Repository repository, Issue issue) {

    OrientGraph graph = dbFactory.getGraph();
    OrientVertex orgVertex = graph.getVertex(new ORecordId(repository.getId()));
    OrientVertex devVertex = graph.getVertex(new ORecordId(issue.getId()));
    orgVertex.addEdge(HasIssue.class.getSimpleName(), devVertex);
  }
}