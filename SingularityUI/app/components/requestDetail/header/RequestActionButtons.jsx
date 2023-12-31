import React, { PropTypes } from 'react';
import { connect } from 'react-redux';
import { withRouter } from 'react-router';

import { Button, DropdownButton, MenuItem } from 'react-bootstrap';
import { Link } from 'react-router';

import JSONButton from '../../common/JSONButton';

import { FetchRequest, FetchRequestShuffleOptOut } from '../../../actions/api/requests';
import {
  FetchActiveTasksForRequest,
  FetchRequestHistory
} from '../../../actions/api/history';

import RunNowButton from '../../common/modalButtons/RunNowButton';
import RemoveButton from '../../common/modalButtons/RemoveButton';
import PauseButton from '../../common/modalButtons/PauseButton';
import UnpauseButton from '../../common/modalButtons/UnpauseButton';
import BounceButton from '../../common/modalButtons/BounceButton';
import ScaleButton from '../../common/modalButtons/ScaleButton';
import ExitCooldownButton from '../../common/modalButtons/ExitCooldownButton';
import EnableHealthchecksButton from '../../common/modalButtons/EnableHealthchecksButton';
import DisableHealthchecksButton from '../../common/modalButtons/DisableHealthchecksButton';

import Utils from '../../../utils';
import ShuffleOptOutButton from '../../common/modalButtons/ShuffleOptOutButton';
import PriorityButton from '../../common/modalButtons/PriorityButton';

const RequestActionButtons = ({ showRequestEdit, requestParent, fetchingShuffleOptOut, fetchRequest, fetchRequestHistory, fetchActiveTasks, fetchRequestShuffleOptOut, router }) => {
  let fetchRequestAndHistoryAndActiveTasks = () => {
    return Promise.all([
      fetchRequest(),
      fetchActiveTasks(),
      fetchRequestHistory(5, 1),
    ]);
  }

  let fetchRequestAndHistory = () => {
    return Promise.all([
      fetchRequest(),
      fetchRequestHistory(5, 1),
    ]);
  }

  if (!requestParent || !requestParent.request || fetchingShuffleOptOut) {
    return <div></div>;
  }
  const { request, state } = requestParent;

  let maybeNewDeployButton;
  if (!config.hideNewDeployButton) {
    maybeNewDeployButton = (
      <Link to={`request/${request.id}/deploy`}>
        <Button bsStyle="success">
          Deploy
        </Button>
      </Link>
    );
  }

  let maybeRunNowButton;
  if (Utils.request.canBeRunNow(requestParent)) {
    maybeRunNowButton = (
      <RunNowButton requestId={request.id} then={fetchRequestAndHistoryAndActiveTasks}>
        <Button bsStyle="primary">
          Run now
        </Button>
      </RunNowButton>
    );
  }

  let maybeExitCooldownButton;
  if (state === 'SYSTEM_COOLDOWN') {
    maybeExitCooldownButton = (
      <ExitCooldownButton requestId={request.id} then={fetchRequestAndHistoryAndActiveTasks}>
        <Button bsStyle="primary">
          Exit Cooldown
        </Button>
      </ExitCooldownButton>
    );
  }

  let maybeScaleButton;
  if (Utils.request.canBeScaled(requestParent)) {
    maybeScaleButton = (
      <ScaleButton
        requestId={request.id}
        currentInstances={request.instances}
        then={fetchRequestAndHistoryAndActiveTasks}
        bounceAfterScaleDefault={Utils.maybe(request, ['bounceAfterScale'], false)}
      >
        <Button bsStyle="primary" disabled={Utils.request.scaleDisabled(requestParent)}>
          Scale
        </Button>
      </ScaleButton>
    );
  }

  let maybePriorityButton;
  if (showRequestEdit || !config.hideNewRequestButton) {
    maybePriorityButton = (
      <PriorityButton
        requestId={request.id}
        current={request.taskPriorityLevel}
        then={fetchRequestAndHistoryAndActiveTasks}
      >
        <Button bsStyle="primary">
          Priority
        </Button>
      </PriorityButton>
    )
  }

  let togglePauseButton;
  if (state === 'PAUSED') {
    if (Utils.request.pauseDisabled(requestParent)) {
      // make sure the action removes the expiring pause
    }
    togglePauseButton = (
      <UnpauseButton requestId={request.id} then={fetchRequestAndHistoryAndActiveTasks}>
        <Button bsStyle="primary">
          Unpause
        </Button>
      </UnpauseButton>
    );
  } else {
    togglePauseButton = (
      <PauseButton requestId={request.id} isScheduled={request.requestType === 'SCHEDULED'} then={fetchRequestAndHistoryAndActiveTasks}>
        <Button bsStyle="primary" disabled={Utils.request.pauseDisabled(requestParent)}>
          Pause
        </Button>
      </PauseButton>
    );
  }

  let maybeBounceButton;
  if (Utils.request.canBeBounced(requestParent)) {
    maybeBounceButton = (
      <BounceButton requestId={request.id} then={fetchRequestAndHistoryAndActiveTasks}>
        <Button bsStyle="primary" disabled={Utils.request.bounceDisabled(requestParent)}>
          Bounce
        </Button>
      </BounceButton>
    );
  }

  let maybeEditButton;
  if (showRequestEdit || !config.hideNewRequestButton) {
    maybeEditButton = (
      <Link to={`requests/edit/${request.id}`}>
        <Button bsStyle="primary">
          Edit
        </Button>
      </Link>
    );
  }

  let maybeToggleHealthchecksButton;
  if (Utils.request.canDisableHealthchecks(requestParent)) {
    if (request.skipHealthchecks) {
      maybeToggleHealthchecksButton = (
        <EnableHealthchecksButton requestId={request.id} then={fetchRequestAndHistory}>
          <Button bsStyle="warning">
            Enable Healthchecks
          </Button>
        </EnableHealthchecksButton>
      );
    } else {
      maybeToggleHealthchecksButton = (
        <DisableHealthchecksButton requestId={request.id} then={fetchRequestAndHistory}>
          <Button bsStyle="primary">
            Disable Healthchecks
          </Button>
        </DisableHealthchecksButton>
      );
    }
  }

  const shuffleOptOutButton = (
    <ShuffleOptOutButton requestId={request.id} then={fetchRequestShuffleOptOut} />
  );

  const removeButton = (
    <RemoveButton
      requestId={request.id}
      loadBalanced={request.loadBalanced}
      loadBalancerData={Utils.maybe(requestParent, ['activeDeploy', 'loadBalancerOptions'], {})}
      then={fetchRequestAndHistoryAndActiveTasks}>
      <Button bsStyle="danger">
        Remove
      </Button>
    </RemoveButton>
  );

  const quickLinks = [];

  Utils.maybe(config.quickLinks, ['request', request.requestType], []).forEach((link) => {
    const maybeLink = Utils.template(link.template, requestParent);
    if (maybeLink) {
      quickLinks.push(
        <MenuItem href={maybeLink}>{link.title}</MenuItem>
      );
    }
  });
  Utils.maybe(config.quickLinks, ['request', 'ALL'], []).forEach((link) => {
    const maybeLink = Utils.template(link.template, requestParent);
    if (maybeLink) {
      quickLinks.push(
        <MenuItem href={maybeLink}>{link.title}</MenuItem>
      );
    }
  });

  return (
    <div>
      <JSONButton linkClassName="btn btn-default" object={requestParent}>JSON</JSONButton>
      {maybeNewDeployButton}
      {maybeRunNowButton}
      {maybeExitCooldownButton}
      {maybeScaleButton}
      {togglePauseButton}
      {maybeBounceButton}
      {maybeEditButton}
      {maybeToggleHealthchecksButton}
      {shuffleOptOutButton}
      {maybePriorityButton}
      {removeButton}
      {quickLinks.length > 0 &&
        <DropdownButton bsStyle="default" title="Quick Links" pullRight>
          {quickLinks}
        </DropdownButton>
      }
    </div>
  );
};

RequestActionButtons.propTypes = {
  requestId: PropTypes.string.isRequired,
  requestParent: PropTypes.object,
  fetchRequest: PropTypes.func.isRequired,
  fetchActiveTasks: PropTypes.func.isRequired,
  router: PropTypes.shape({ push: PropTypes.func.isRequired }).isRequired,
};

const mapStateToProps = (state, ownProps) => ({
  requestParent: Utils.maybe(state.api.request, [ownProps.requestId, 'data']),
  fetchingShuffleOptOut: Utils.maybe(state.api.shuffleOptOut, [ownProps.requestId, 'isFetching'], true),
  showRequestEdit: Utils.maybe(state.api.user, ['data', 'user', 'groups'], []).includes(config.showRequestButtonsForGroup),
});

const mapDispatchToProps = (dispatch, ownProps) => ({
  fetchRequest: () => dispatch(FetchRequest.trigger(ownProps.requestId, true)),
  fetchRequestHistory: (count, page) => dispatch(FetchRequestHistory.trigger(ownProps.requestId, count, page)),
  fetchActiveTasks: () => dispatch(FetchActiveTasksForRequest.trigger(ownProps.requestId)),
  fetchRequestShuffleOptOut: () => dispatch(FetchRequestShuffleOptOut.trigger(ownProps.requestId)),
});

export default withRouter(connect(
  mapStateToProps,
  mapDispatchToProps
)(RequestActionButtons));
