import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';
import { withRouter } from 'react-router';

import rootComponent from '../../rootComponent';
import * as RefreshActions from '../../actions/ui/refresh';
import { FetchCostData } from '../../actions/api/costs';
import { FetchRequest } from '../../actions/api/requests';
import {
  FetchActiveTasksForRequest,
  FetchTaskHistoryForRequest,
  FetchDeploysForRequest,
  FetchRequestHistory
} from '../../actions/api/history';
import {
  FetchScheduledTasksForRequest,
  FetchTaskCleanups
} from '../../actions/api/tasks';

import CostsView from './CostsView';
import RequestHeader from './RequestHeader';
import RequestExpiringActions from './RequestExpiringActions';
import ActiveTasksTable from './ActiveTasksTable';
import PendingTasksTable from './PendingTasksTable';
import TaskHistoryTable from './TaskHistoryTable';
import DeployHistoryTable from './DeployHistoryTable';
import RequestHistoryTable from './RequestHistoryTable';
import RequestUtilization from './RequestUtilization';

import Utils from '../../utils';

import { refresh, initialize } from '../../actions/ui/requestDetail';

class RequestDetailPage extends Component {
  componentDidMount() {
    this.props.refresh();
    if (config.costsApiUrlFormat) {
      const { requestId } = this.props.params;
      this.props.fetchCostsData(requestId);
    }
  }

  componentWillReceiveProps(nextProps) {
    if (nextProps.params !== this.props.params) {
      refresh(nextProps);
    }
  }

  componentWillUnmount() {
    this.props.cancelRefresh();
  }

  render() {
    const { deleted, router, location, params } = this.props;
    const { requestId } = params;
    const { taskHistoryPage, taskHistoryPageSize } = location.query;
    return (
      <div>
        <RequestHeader requestId={requestId} showBreadcrumbs={this.props.showBreadcrumbs} deleted={this.props.deleted} />
        {deleted || <RequestExpiringActions requestId={requestId} />}
        {deleted || <ActiveTasksTable requestId={requestId} taskHistoryPage={Number(taskHistoryPage) || 1} taskHistoryPageSize={Number(taskHistoryPageSize) || 10} />}
        {deleted || <PendingTasksTable requestId={requestId} />}
        {deleted || (
          <TaskHistoryTable
            requestId={requestId}
            location={this.props.location}
            initialPageSize={Number(taskHistoryPageSize) || 10}
            onPageChange={num => router.replace({ ...location, query: {...location.query, taskHistoryPage: num }})}
            initialPageNumber={Number(taskHistoryPage) || 1}
          />
        )}
        {deleted || <CostsView requestId={requestId}/>}
        {deleted || <RequestUtilization requestId={requestId} />}
        {deleted || <DeployHistoryTable requestId={requestId} />}
        <RequestHistoryTable requestId={requestId} />
      </div>
    );
  }
}

RequestDetailPage.propTypes = {
  params: PropTypes.object.isRequired,
  refresh: PropTypes.func.isRequired,
  cancelRefresh: PropTypes.func.isRequired,
  deleted: PropTypes.bool,
  showBreadcrumbs: PropTypes.bool
};

const mapStateToProps = (state, ownProps) => {
  const statusCode = Utils.maybe(state, ['api', 'request', ownProps.params.requestId, 'statusCode']);
  const history = Utils.maybe(state, ['api', 'requestHistory', ownProps.params.requestId, 'data']);
  return {
    notFound: statusCode === 404 && _.isEmpty(history),
    deleted: statusCode === 404,
    pathname: ownProps.location.pathname
  };
};

const mapDispatchToProps = (dispatch, ownProps) => {
  const refreshActions = [
    FetchRequest.trigger(ownProps.params.requestId, true),
    FetchTaskCleanups.trigger(),
    FetchActiveTasksForRequest.trigger(ownProps.params.requestId),
    FetchScheduledTasksForRequest.trigger(ownProps.params.requestId),
  ];

  return {
    refresh: () => {
      dispatch(RefreshActions.BeginAutoRefresh(
        `RequestDetailPage-${ownProps.index}`,
        refreshActions,
        4000
      ));
    },
    cancelRefresh: () => dispatch(
      RefreshActions.CancelAutoRefresh(`RequestDetailPage-${ownProps.index}`)
    ),
    fetchCostsData: (requestId) => dispatch(FetchCostData.trigger(requestId, config.costsApiUrlFormat)),
    fetchRequest: (requestId) => dispatch(FetchRequest.trigger(requestId, true)),
    fetchTaskCleanups: () => dispatch(FetchTaskCleanups.trigger()),
    fetchTaskHistoryForRequest: (requestId, count, page) => dispatch(FetchTaskHistoryForRequest.trigger(requestId, count, page)),
    fetchDeploysForRequest: (requestId, count, page) => dispatch(FetchDeploysForRequest.trigger(requestId, count, page)),
    fetchRequestHistory: (requestId, count, page) => dispatch(FetchRequestHistory.trigger(requestId, count, page))
  };
};

export default withRouter(connect(
  mapStateToProps,
  mapDispatchToProps
)(rootComponent(
  RequestDetailPage,
  (props) => refresh(props.params.requestId),
  true,
  true,
  (props) => initialize(props.params.requestId),
)));
