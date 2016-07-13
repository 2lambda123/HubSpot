import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';

import { SkipRequestHealthchecks } from '../../actions/api/requests';

import FormModal from '../common/modal/FormModal';

class EnableHealthchecksModal extends Component {
  static propTypes = {
    requestId: PropTypes.string.isRequired,
    enableHealthchecks: PropTypes.func.isRequired
  };

  show() {
    this.refs.enableHealthchecksModal.show();
  }

  render() {
    return (
      <FormModal
        ref="enableHealthchecksModal"
        action="Enable Healthchecks"
        onConfirm={(data) => this.props.enableHealthchecks(data)}
        buttonStyle="primary"
        formElements={[
          {
            name: 'durationMillis',
            type: FormModal.INPUT_TYPES.DURATION,
            label: 'Expiration (optional)',
            help: 'If an expiration duration is specified, this action will be reverted afterwards.'
          },
          {
            name: 'message',
            type: FormModal.INPUT_TYPES.STRING,
            label: 'Message (optional)'
          }
        ]}>
        <p>Turn <strong>on</strong> healthchecks for this request.</p>
        <pre>{this.props.requestId}</pre>
      </FormModal>
    );
  }
}

const mapDispatchToProps = (dispatch, ownProps) => ({
  enableHealthchecks: (data) => dispatch(SkipRequestHealthchecks.trigger(
    ownProps.requestId,
    {...data, skipHealthchecks: false}
  )),
});

export default connect(
  null,
  mapDispatchToProps,
  null,
  { withRef: true }
)(EnableHealthchecksModal);
